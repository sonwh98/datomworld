# The canonical instruction vector contract -- revision history

Revision: 1 (2026-09-25) -- architecture-owner publication of the
history and the static safepoint-kind ruling in section 8.

Status: authoritative revision history for the `v2` stamp in
`src/cljc/yin/vm/ucf.cljc:38`. It completes the publication indexed by
`yin.vm.semantic.md` section 2.4 ("semantic.md" below), closing the
documentation part of UCF section 7.11's contract-revision-publication
blocker and the Phase 1 sign-off's P2 publication gate. This ruling does
not accept UCF as a whole; UCF 7.11's other gates and tests remain.
(2026-10-15: the stamp carrier and the "semantic.md" referent are both
deleted — entry I-6 below records the phase-8 cutover; the file name
`yin.vm.semantic.md` now belongs to the register-shaped design, and this
document is the authority for the historical `"v2"`/`"v3"` contracts.)

Quotes below are verbatim except that section marks are written `S`
and em dashes `--`, so every line here stays ASCII.

## 1. Purpose, referent, and validity rule

The `v2` stamp (`src/cljc/yin/vm/ucf.cljc:38-47`,
`{:yin.code/contract "v2", :yin.k/version 0}`) names one published
revision of the complete execution contract. UCF section 7.3.3 lists
what one revision names, and `yin.vm.semantic.md` ("semantic.md"
below) section 2.4's "The `"v2"` execution contract, published" note
is the index that gives each component its home. This file publishes
the history of that named contract: per revision, what changed, which
sections it amended, the commit or artifact that carried it, and
whether already-published images remain valid under it.

Validity is judged by UCF's own contract-version semantics:

- An engine's code index is keyed per stamp:
  `(get-in index [stamp address])`; the same address under different
  stamps is a different image (UCF 7.3.3).
- "Two revisions may be declared compatible only by a published
  statement in `yin.vm.semantic.md` S2.4, never by an engine's own
  judgment" (UCF 7.3.3).
- semantic.md section 2.4 declares no cross-revision compatibility.
  A form-level change needs a new contract name if images exist; none
  existed for D-0 through D-3. Later amendments below are classified
  by whether they change a named component or only its carrier.
- The UCF top warning stands "until each of these is closed with a
  test in the parity or conformance suite" (UCF 7.11).

Scope notes. (a) The UCF sections cited live in
`docs/design/yin.vm.universal-continuation-format.md` since commit
3325815d extracted them from semantic.md; semantic.md section 7 is a
pointer note. (b) The stamp's referent is the components of UCF 7.3.3
/ semantic.md 2.4 (section 2 here); revisions to other UCF sections
are listed only when they touch the code-identity section 7.3, or
marked as outside the referent.

## 2. What one revision names (the referent)

Per semantic.md section 2.4, the `v2` contract is, by reference:

- Mnemonic set: the section 2.4 opcode table's `:yin.code/op` column,
  21 values, matching `yin.vm.code/mnemonics`.
- Per-mnemonic arity and operand kinds: the section 2.4 "Operand
  attributes" column; enforced by section 2.6 rule 2
  (`:instruction-shape`) and rule 5 (`:dangling-target`).
- Saturation/defaults: semantic.md 2.4 "Saturation and defaults",
  corrected by section 7.5 below for `:call`'s absent tail flag
  and the unreachable `:ffi-call` argc fallback.
- Opcode table and transitions: semantic.md 2.4 "Opcode integers" and
  the section 4.2 transition equations.
- Resolution rule: section 4.2's rho = `engine/resolve-var`,
  env -> store -> primitives -> module registry.
- Last-value-wins rule: a repeated single-valued instruction
  attribute keeps its last value in datom order, as `code.cljc`'s
  `index-batch` and `yin.vm/index-datoms` both implement it.
- Effect outcome map: semantic.md section 3.3's table.
- Scheduler semantics: semantic.md section 3.5.

Load-bearing but inherited, not named by the stamp: the address basis
`(dao.jing/segment-key vector)`. UCF 7.3.2: "UCF must not mint a
second addressing scheme". Both the default digest and the byte
encoding beneath `segment-key` changed before the Phase 1 stamp
landed (I-0 and I-1 below). UCF's `:segment/sha256-...` examples
predate the digest change; current implicit minting uses BLAKE3.

## 3. Design-phase revisions (before UCF execution)

No UCF-stamped image existed in this period, so stamped-image validity
is vacuous. D-0 did have Phase 0 executable code; it did not implement
UCF addressing. D-1 and D-2 are known only as named predecessors in
UCF 7.3.2; their artifacts are absent from `collab/` (section 7.3).

D-0. The reservation (semantic.md 2.2). `:yin.code/hash` is reserved:
"Reserved: content address of the canonical instruction datoms, for
dedup and verification (`streams-all-the-way-down.md` S6.3). Not
required in Phase 1." Carrier: semantic.md as promoted from the
Phase 0 findings note (collab/1789221648668-architect-semantic-
vm-v2-design.claude-fable-5-1.findings.md, absent from `collab/`);
in-tree from commit 27fc0f9a (2026-09-13).
Sections amended: none -- first statement. UCF-stamped images: none.

D-1. The first draft (2026-09-14). Canonical form: sorted `[e a v]`
triples over the batch. UCF 7.3.2's verdict, quoted: "the first
draft's sorted `[e a v]` triples, which were neither total nor
execution-faithful" -- constants arriving `1, 2` versus `2, 1` sorted
identically while executing differently, the collision the review
demonstrated. Carrier: the 2026-09-14 first draft; no in-tree commit
carries this text (the earliest committed UCF text already postdates
it). Sections amended: UCF 7.3.2 (then section 7.3 of the semantic.md
appendix). Images: none existed.

D-2. The r2 revision (2026-09-14). Canonical form: the attributed-map
record `{pc -> {attr -> value}}`, answering findings 1-19 of the
architectural review
(`collab/yin-ucf/1789387292000-architect-ucf-review.gpt-6-astra.findings.md`,
absent from `collab/`). UCF 7.3.2's verdict, quoted: "sound but
carried datom vocabulary into the identity layer -- attribute names
and admissibility rules where position and the opcode table suffice".
Carrier: the 2026-09-14 r2 revision; no in-tree commit carries this
text. Sections amended: UCF 7.3.2. Images: none existed.

D-3. The r3 owner ruling (2026-09-14). Canonical form: the
positional instruction tuple vector -- one tuple per instruction, pc
is the index, the semantic.md 2.4 table fixes each mnemonic's tuple
arity and operand kinds, nothing else names a slot. Two owner rulings
are recorded in UCF 7.3.2: tuples are the correct way to represent a
semantic AST, though the datom `[e a v t m]` need not be the tuple
shape when content-hashing code; and `dao.space.query` assumes no EAV
(`src/cljc/dao/space/query.cljc:153-158`), so Datalog-over-code does
not require `[e a v t m]` and UCF must not assume it anywhere for
code. The same ruling enlarged the stamp: "the first draft pinned
only the opcode table -- leaving resolution precedence, effect
outcomes, call restoration, and scheduling outside the stamp" (UCF
7.3.3); r3 versions the complete execution contract. Sections
amended: UCF 7.3.2 wholly; 7.3.3's stamp scope. Carrier: the UCF
document itself, whose header records "amended 2026-09-14 (r3) by
owner ruling"; first in-tree carrier ae76fe0d (2026-09-18). Images:
none existed.

## 4. In-tree publication and amendments

P-1. semantic.md indexes the contract (3e987123, 2026-09-17
21:47:13 +0700, "publish the ast-to-bytecode lowering profile (D1)").
semantic.md 2.4 gains "The `"v2"` execution contract, published":
each of the components of section 2 here indexed to its home, "the
whole `"v2"` contract by reference, not a duplicate of it", and the
document header gains its first revision line. What changed: the
contract's name and homes became published text; a sequence of
revisions did not. Thus its header's claim to have closed UCF 7.11
was premature (section 7.1). The commit also corrected the opcode
alias, defaults, and well-formedness description. Sections amended:
semantic.md 2.4, 2.6, and header. Images: none existed. The header
self-dates 2026-09-18; the commit is dated 2026-09-17 (section 7.2).

P-2. UCF baseline committed (ae76fe0d, 2026-09-18 10:57:45 +0700,
"U12: Commit UCF baseline"). First committed carrier of UCF
7.3.1-7.3.4 and 7.4 as current text: the resolved-interpretation
input rule (a repeated single-valued attribute keeps its last value;
"the load-bearing choice"), saturation of defaulted operands,
admissible slots, the no-ordering rule, the address
`(dao.jing/segment-key vector)`, the addressed object being the
vector itself, the two loading paths, the three-step resolution
order, and two-check verification (hash, then grammar). What changed
relative to D-3: nothing in form -- this is the first durable
carrier. Sections amended: none. Images: none existed.

P-3. Direct path made primary (b82813d8, 2026-09-18 11:00:43 +0700,
"U12: Amend UCF r3 for D6 resolutions"). UCF 7.3.4: the projection
path is no longer the reference -- `yin.vm.code-as-tuples.md` section
7.2 ("Conformance: both paths, one validator, one image") supersedes
that designation; "the direct path is primary and is the reference;
the projection path is derived from it. Both invoke the same
validator." Sections amended: UCF 7.3.4 (loading); the stamp's named
components are untouched. Images: none existed.

P-4. Split parked-id ruling (66d55abd, 2026-09-18 12:26:28 +0700,
"U14"). Amends UCF 7.6.3 (referenced parked records): a missing id
referenced as a code operand is reported `:yin.k/unsatisfied`; one
referenced by an active value refuses the lift
(`:yin.k/non-portable`, kind `:foreign-parked-ref`). Listed for
completeness only: outside the stamp's referent.

P-5. Editorial (a76674a5, 2026-09-20 17:05:41 +0700, "W5"). Two lines
of the UCF doc rewritten to name the engine for the woken-writer
retry. No contract semantics changed; the scheduler-semantics
component (semantic.md 3.5) is unaffected on its own text.

## 5. Implementation and integration records

I-0. Address algorithm registry (0aa710e7, 2026-09-23 16:50:31
+0700, H1; hardened by f05599f7, 17:35:50 +0700, H2).
`dao.jing/segment-key` changed its implicit minting default from
SHA-256 to BLAKE3 and introduced a closed algorithm registry.
Before the CBOR swap, existing SHA-256 addresses remained verifiable
by their embedded algorithm, but re-minting with the new default earned
a different address. `semantic.cljc` changed to verify a claimed
address by its algorithm. The `v2` vector and execution semantics
did not change; no UCF-stamped image yet existed. UCF 7.3.2's
SHA-256 example and section 4.1 of `yin.vm.code-as-tuples.md`
describe the earlier address era, not the current default.

I-1. Address basis: canonical CBOR (3ddaa21b, 2026-09-25 06:56:59
+0700; merge e149aa31, "dao-jing-cbor-swap"). `dao.jing/segment-key`
now derives from `dao.jing.cbor/encode`; dao.jing.md's open item is
closed as "Retired by the canonical CBOR codec (2026-09-24)": the
transitional order-normalized printer and its residuals (symbol
metadata not address-significant, pathological print collisions,
ambient print-var leakage) are gone. What changed: the vector form
and the stamp did not; the address function beneath `segment-key`
did -- exactly the event UCF 7.3.2 forecast: "when the pinned byte
encoding lands, `:yin.code/hash` values change with every other
minted address." Sections amended: none in UCF; the inherited basis
it cites. Image validity: addresses minted under the transitional
printer do not verify after the swap and must be re-minted.
In-tree, no UCF-stamped image predates the swap: the phase 1 commit
landed 21 seconds later, in the same merge window.

I-2. UCF phase 1: the stamp becomes executable (f51077f2, 2026-09-25
06:57:20 +0700; merge c2b110a6, "ucf-phase1"). Carriers and changes:

- `src/cljc/yin/vm/ucf.cljc` (new): `contract-stamp` (ucf.cljc:38-47)
  is the first executable carrier of
  `{:yin.code/contract "v2", :yin.k/version 0}`;
  `batch->canonical-instruction-vector` implements UCF 7.3.2
  (index-batch collapse, saturation defaults, refs resolved to pcs,
  `:non-canonicalizable` refusals, and a `well-formed-vector?` check
  so the function only yields vectors `load-vector` accepts);
  `code-address` delegates to `(jing/segment-key v)`; `canonicalize`
  bundles the UCF 7.9 outcome; `safepoints` derives the static half
  of UCF 7.4.2, plus `:yin.safepoint/reasons` (ucf.cljc:351),
  corrected by the section 8 ruling.
- `src/cljc/yin/vm/semantic.cljc` (+6/-12): a claimed
  `:yin.code/hash` is now verified by re-canonicalizing the batch
  through `ucf/batch->canonical-instruction-vector` -- the saturated
  vector -- instead of the loader's former local, unsaturated
  `canonical` fn (the reviewer cites the removed fn at semantic.cljc
  585-599 pre-phase-1; the verification now sits at semantic.cljc
  605-616). The reviewer verified this as "the intended, documented
  fix for the unsaturated local canonicalizer; the coupling is
  justified."
- `test/yin/vm/ucf_test.cljc` (new, 622 lines).

Sections amended: none in the design docs; this revision binds the
published text to executable behavior for the first time. Image
validity: phase 1 is the first carrier of the `v2` stamp, so there
is no earlier stamped image to invalidate, and per UCF 7.3.3 stamped
code indexes start here. Holding the current `dao.jing` encoder and
algorithm fixed, an unstamped batch stating every defaulted operand
keeps its former address; one omitting a defaulted operand now earns
the saturated address, so an address minted by the pre-phase-1
unsaturated path for that batch no longer verifies. The architect
sign-off records the intent: "The loader's
executable image remains unchanged for accepted batches; claimed-
hash acceptance changes intentionally."

Gates recorded on I-2 (not yet discharged in-tree):

- Reviewer verdict READY with seven P3s, none blocking
  (`collab/yin-ucf/1790268690622-reviewer-ucf-phase1.glm-flash.findings.md`),
  including the `:reasons` finding (section 8 here) and the
  saturation nil-vs-truthiness P3 at ucf.cljc:171.
- Architect verdict GRANTED with Phase 2 gates
  (`collab/yin-ucf/1790280923723-architect-ucf-phase1-signoff.gpt-6-sol.stdout.log`):
  P2 -- the revision history (this document); P3 -- `:reasons`
  (section 8 here); P3 -- the transitional encoder's metadata-
  bearing payloads ("Storage owner: preserve or refuse metadata-
  bearing payloads before UCF publishes them"), which I-1's codec
  swap subsequently closed on the encoder side per dao.jing.md; the
  gate's owner action is not recorded as discharged anywhere
  in-tree.

I-3. Linker consumer specified (1f7990d5, 2026-09-25 11:42:15 +0700,
"add the yin.vm.linker master specification (r11 consensus)"). The
linker spec's section 5.2 fixes the `:yin.semantic/code` format
record with `:contract "v2"`: "a link request for this format
carries the stamp, and the receiver's index is keyed per stamp (UCF
section 7.3.3)". The format field names the execution revision;
`:yin.k/version 0` remains the UCF envelope version. Loading is
`yin.vm.semantic/load-vector`. This is a design consumer, not a new
runtime implementation or a contract revision. The M2 attempt is gated
REQUEST CHANGES / Sign-off DENIED
(`collab/yin-linker/1790320431837-architect-linker-m2-gate.gpt-6-sol.stdout.log`,
2026-09-25 07:17:54 GMT) on record-scanner, Dart-typing, and
fetch-bounds P1s -- implementation defects, not contract revisions.

I-4. Rule R: the contract moves to v3 (0fc931fc, 2026-09-26 05:06:02
+0700, "make yin/def syntax, never a name"). `yin/def` stopped being a
primitive found by name: `engine/resolve-var` refuses it before the
environment and store, the definition transition in all four engines
never resolves its operator, and `:define` opcodes replace the call
shape. That changed the resolution precedence and added a transition,
both named components of the stamp (UCF 7.3.3), so every persistent
code format took a new revision name (`yin.vm.cljc`: `ast-contract`
"v3", `semantic-contract` "v3", `stack-contract` "b2",
`register-contract` "r2"). Carriers: `ucf.cljc`'s `contract-stamp` now
carries `{:yin.code/contract "v3", :yin.k/version 0}` through
`vm/semantic-contract`; every persistent-code loader requires and
compares a stamp and refuses `:contract-missing` or
`:contract-mismatch` before validation. The stamp is never assigned to
external input.

Sections amended: UCF 7.3.3 (the revision named by the stamp),
semantic.md 2.4, and the stack, register, code-as-tuples, macro and
engine design documents, which carry the same commit. Image validity:
images stamped "v2", "b1" or "r1" no longer load and there is no
migration; the repository is development-only, with no deployed stores,
so no published image is stranded. `yin.vm.linker.md` section 8.1 makes
the same rule for manifests: a manifest naming "v2", "b1" or "r1" is
`:contract-mismatch`.

I-5. Stage 1 of the post-M5 hardening (linker-dht 14.1; the
linker-hardening worktree, 2026-10-02, commit to be recorded by the
seat when it lands). Two carriers. First, section 8's naming
correction is executable: `ucf.cljc`'s static safepoint map now
publishes `:yin.safepoint/kinds` (`ucf/parking-kinds`) with the
ruling's values, and `:yin.safepoint/reasons` is gone; `ucf_test`'s
pins migrated with it. Second, `yin.vm.ucf.handoff` is the 14.1.2
lift/lower driver over canonical CBOR bytes (see section 6 for what
its stage-2 amendment must carry). No named component of the stamp
changed: code stamps, the vector grammar and cursor bytes are
untouched, so no image validity changes.

## 6. Pending amendments named but not landed

- The stage-2 amendment of the post-M5 hardening (M-next B,
  linker-dht 14.3 item 2) is PUBLISHED DESIGN, not implemented
  version-1 support. Record, 2026-10-04:
  - r5 published `:yin.k/version 1` in the UCF document: 7.2.1 (the
    handoff body, the version gate, the custody header), 7.4.3
    (explicit park, the complete install child, operation ids on
    retained writes, required keys per variant), 7.7.8 (sequence
    state, the fenced envelope, the grant epoch binding, admission),
    7.9 (the `:yin.k/admission` family), and a ten-clause block in
    7.11.1; linker-dht 14.2.2 and 14.3 point to it. Its
    reconciliation of the 14.2.2 proposal against the UCF text and
    the landed wire is in collab
    `1791055853000-architect-ucf-v1-amendment-m-next-b` (findings).
  - The second architect reviewed r5 adversarially: accept with
    changes (collab `1791056670000-architect-ucf-v1-amendment-review`).
  - r6 reconciled that review into the same sections and 7.2, 7.7.6:
    inherited ids scoped to the granted checkpoint's carried
    pendings on a single acyclic successor chain; one dedup
    namespace per admission resource; authenticated outcomes;
    uncertainty separated from terminal refusal; epoch exhaustion
    precedence; root-only custody header and context-sensitive child
    validation; the defective-envelope diagnostic; quarantine after
    an authoritative intent conflict; enrollment as an authority
    fact; occurrence-id invariants.
  - The second architect confirmed r6 with changes (collab
    `1791057600000-architect-ucf-v1-amendment-r6-confirm`), and r7
    applied them in 7.4.3, 7.7.8 and 7.11.1: checkpoint
    unavailability suspends an inherited id after the tenure check
    and mutates nothing; snapshot variants preserve the operation
    baseline; a live install entry's phase (`:running` or `:parked`)
    no longer constrains the child's kind; the diagnostic defect set
    is defined in full and its stream is composition-supplied;
    unknown transport acceptance is neither commitment nor its
    absence, and the retry replays a result already held.
  - Carrier: uncommitted at the time of writing; the seat records
    the commit when it lands.
  - Not changed: code stamps "v3", "b2", "r2", the vector grammar,
    module manifest schema 1, and cursor bytes. The amendment raises
    the handoff body's own top-level `:yin.k/version`; the key of
    that name inside the stamp stays 0. No image validity changes.
  - Still pending: all implementation. `handoff-version` is 0.
    M-next C, D, and E and every piece of version-1 evidence in UCF
    7.11.1 and linker-dht 14.2.4 remain owed.
    Status, 2026-10-05: M-next C slices C1 to C5 are implemented
    (C1 dc7d493e, C2 bec1e3af, C3 3608a791, C4 d3d4a188, C5
    05140677), and the amendments C5 forced landed: UCF 7.2.1 (the
    occurrence form), 7.7.2 (the grantor's facts) and 7.7.8 (the
    view and author behind a binding, the offer order). C6 to C12,
    D and E remain; `handoff-version` is still 0.
    Status, 2026-10-05: M-next C slices C1 to C11 are implemented
    (C6 9e53b93c 9153b75e d484080d, C7 919f7db7 3bbb9856, C8
    59479d9d, C9 9352001f, C10 51efd46c 268c4f4b, C11
    485fe1a1), and the amendments C6 to C11 forced landed: UCF 7.7.2
    (the grantor's further facts and the completion facts as
    recorded), 7.7.6 (the halted result), 7.7.7 (the reopen steps),
    7.7.8 (the epoch change, the refusal pair, exhaustion in the
    ledger, the 2^52-1 seq, inherited-id order, successor offers,
    quarantine and completion) and 7.9 (intent digests, the
    arbitration shape, the outcome projection, trusted identities);
    `dao.lease.md` *Composition duties* (the reclaim adapter);
    linker-dht 14.2.2 (the input protocol, the cost note) and
    14.2.4 (the authority's front). C12, D and E remain;
    `handoff-version` is still 0.
    Status, 2026-10-07: M-next C slice C12 (the stage-C gate:
    `authority/durability` and `exclusive-capable?`, the crash-cut
    matrix, the cross-host ledger fixture) and M-next D slices D1 to
    D9 are implemented (C12 7c62dbde; D1 90bd8e57, D2 7349219d, D3
    f4961daa, D4 ee3498de, D5 8efde902, D6 52a0383b, D7 98c84713, D8
    baa3796a, D9 d974289e), and the amendments they forced landed: the
    C12 sentences in 7.7.7 (enrollment retry), 7.11.1 (the stage-C
    boundary), linker-dht 14.2.1 (`exclusive-capable?`) and
    `dao.stream.journal.md` (`:persisted`, the cost note); the D-stage
    sentences in 7.7.4 (the gate modes, the custody holds, the header
    as the lift's input, the self-check, the abort rule and tenure)
    and linker-dht 14.2.2 (the enrolled set is a lift input and never
    travels). After D6 the custody gate is complete as a fence and as
    a protocol: every observation a gated task parks has a public
    apply. `handoff-version` is `#{0 1}`; a `nil` lift header selects
    the frozen version-0 wire, and a supplied custody header selects
    version 1. D10 to D16 and E remain; the doc text for the
    protection classes and the fenced writer lands with D11.
    Status, 2026-10-08: M-next D slices D10 to D14 are implemented
    (D10 441b2b4c, D11 4c4764f7, D12 b38786c8, D13 bf6bdeaa, D14
    9cea60dd). Stage D10b-A/B landed the version-2 handoff body as
    the standalone amendment
    `yin.vm.universal-continuation-format.v2-amendment.md` and its
    four-kernel lift/lower (da325495, 49960017), closing the four
    execution profiles (semantic, stack, register, walker) with no
    code-format, opcode or stamp change; `handoff-version` is now
    `#{0 1 2}`. Stage D15a landed the holder-driver control/program
    split with durable positional inboxes (2cf99c13):
    `control-step`, `program-step`, `stop` and
    `owed-control-write?` over the ruled version-1 inbox
    descriptors, the unavailable-lane semantics and the split accept
    tail, documented in UCF 7.11. The doc amendments these stages
    forced are recorded in 7.7.4, 7.9 and linker-dht 14.2.2 (the
    driver split, the refusal and hold vocabularies, the input
    conflict rule and the write-ahead journal). D15, D16 and E
    remain. D16's entry is gated on the deepseek findings F2 and F3:
    F2, the Node run and the full twelve-row matrix on JVM, Node and
    Dart, row 10's canonical-byte agreement provable only
    cross-host; F3, a one-line-per-row traceability of the twelve
    acceptance rows to concrete test names, with any gap filled. F1
    (rename the load-bearing `v1?` flag) is a readability cleanup,
    not a D16 gate.
  - Recorded separately, as version-0 defects of the landed stage-1
    handoff and not as version-1 gaps: (a) lower assigns an empty
    wait set for a `:parked` body that also carries frames
    (`handoff.cljc`, about line 1386); (b) `validate-body` does not
    require an install entry for every `:install` pending (about
    line 996); (c) the version-0 export's address was the content
    hash of the body map, not of the emitted bytes, so it never
    verified against the fetched payload (D1 90bd8e57). Each is a
    post-A fix owed a version-0 test, delivered in D (linker-dht
    14.3). Neither reopens stage 1's kept-cursor evidence nor
    reassigns the M4 gate. Status: all three fixed in version 0,
    each with its own red-then-green test in
    `yin.vm.ucf.handoff-test`; the wire is unchanged.

  Stage 1 (M-next A, the kept-cursor handoff of linker-dht 14.1,
  carried by `yin.vm.ucf.handoff`) recorded here what the amendment
  had to carry beside its own custody fields; r5 to r7 publish each
  item:
  1. The handoff body envelope as wire fact: tag `:yin.k/handoff`,
     version, kind (`:blocked`, `:parked`, `:halted`), contract stamp,
     ordered frames of registers plus pending, the parked-record slice
     and its active id, install children as whole child bodies with
     phase and response, the isolated store and module-store
     snapshots, the one cursor-cell table, closed requirements (cursor
     profiles, segments), the code vectors under their addresses, the
     fresh-name counter, and the halt result.  Stage 1 mints these as
     version-0 canonical CBOR bytes under their content address; the
     amendment names them UCF grammar, not implementation detail.
  2. The explicit-park no-wait shape 7.4.1 lacks: no pending variant
     exists for `:park`; the parked record travels in the scheduler
     slice under its own id and no wait is minted.  Stage 1 implements
     this; the amendment publishes it.
  3. The install-child completeness shape: an `:install` pending names
     a module whose live child, phase and response travel as the
     child's own handoff body; a name alone refuses before publication
     (`:yin.k/non-portable`, kind `:incomplete-install`).  An
     obligation a live install child will bind on completion is
     discharged by that carried child, not refused as missing.
  4. The dynamic-reason rule of section 8, executable since stage 1
     through `:yin.safepoint/kinds`: the kinds gate pc eligibility
     only; the wire reason and pending variant come from the observed
     entry, and a variant the static kinds do not admit refuses (kind
     `:reason-mismatch`), never invents `:call-effect`.
  5. Cursor-cell identity discipline: cursor-cell ids are lift-local, minted once
     across the whole reachable graph (frames, waits, stores, closures,
     parked records); the lower allocates fresh resource keys per cursor cell
     and re-seals every reference under the receiver. (The version-2
     code spells the cursor cells `:yin.k/cells`, `:yin.k/cell`,
     `:yin.k/c-<n>`; version 3 spells them `:yin.k/cursor-cells`,
     `:yin.k/cursor-cell`, `:yin.k/cc-<n>`.)
  6. The cursor-profile claim: a body carrying cursor cells must claim
     `:dao.stream.remote/v1` in `:yin.k/requires`; omission refuses
     `:yin.k/unsatisfied` before publication.
- Contract-pinned AST and semantic identities. From the linker
  spec's open items: "Pinning `:yin.ast/code` and
  `:yin.semantic/code` identities to a contract hash, with explicit
  identity-to-address indexes, is an amendment to UCF section 7.3.2
  and `yin.vm.code-as-tuples.md` section 4.1." Named, not landed; it
  would amend the referent's address semantics. Section 4.1 concerns
  the root-row grain of AST identity, not the UCF vector's grain;
  the proposed index must keep those two distinct.
- M4's "the UCF table amendments" (linker spec section 9) are the
  portable-encoding amendments of linker spec section 11, item 12 --
  UCF 7.5.1/7.5.3 material. Outside this stamp's named components,
  but required before the M4 lift driver runs.
- UCF 7.11's warning stands until each blocker closes with the tests
  it requires. Publication is complete here; conformance is not.

## 7. Source disagreements and owner rulings

7.1 Publication status. semantic.md (3e987123) claimed publication:

    Revision: 1 (2026-09-18) -- the `"v2"` execution contract this
    document names is published in full by S2.4's "v2 contract
    revision history" note, per yin.vm.universal-continuation-
    format.md S7.11's contract-revision-publication blocker. Prior
    text carried no revision line; this is the first.

and, in the note itself: "All seven are already written, here or by
direct citation." Against it, UCF 7.11 (in-tree today) still
requires "a published revision history naming the complete execution
contract (S7.3.3) ... written into `yin.vm.semantic.md` S2.4 beside
the opcode table", and the architect sign-off (2026-09-24 20:18 GMT,
i.e. after 3e987123) still ruled:

    P2, Phase 2 gate | ucf.cljc:38 | The `v2` stamp names a contract
    whose complete revision history is not yet published, as S7.11
    requires. | Architecture owner: publish the revision before
    cross-host lowering.

Ruling: the sign-off and UCF 7.11 stood at the time. semantic.md 2.4
published the component index and its then-current substance, but no
chronological revision history. Its header overstated that publication.
This document now supplies the missing history, with semantic.md 2.4
remaining the index of each component's operative definition. The
amended semantic.md header points here. The publication gate is now
closed; M4 still must demonstrate the separate UCF conformance gates.

7.2 Dates. The semantic.md header self-dates its revision line
"2026-09-18"; its carrier commit 3e987123 is dated 2026-09-17
21:47:13 +0700. The UCF header dates r2 and r3 "2026-09-14"; their
first in-tree carrier ae76fe0d is dated 2026-09-18. Cited as
recorded; no ordering is asserted beyond the commits' own dates.

7.3 Absent artifacts. Two artifacts named by in-tree text were never
committed to this repository (verified across all refs): the Phase 0
findings note (collab/1789221648668-architect-semantic-vm-v2-design
.claude-fable-5-1.findings.md, named by the semantic.md header) and
the 2026-09-14 UCF architectural review (collab/1789387292000-
architect-ucf-review.gpt-6-astra.findings.md, named by the UCF
header). The D-0/D-1/D-2 history therefore rests on the surviving
documents' own testimony of them.

7.4 The static reasons field. UCF 7.4.2's published static map lists
`:yin.safepoint/segment`, `/engine`, `/pc`, `/stack-effect`,
`/lexically-required`, `/layout` -- no reasons field. Phase 1's
`safepoints` publishes `:yin.safepoint/reasons` (ucf.cljc:351)
anyway, described as "the reasons under which the machine parks
there" (ucf.cljc:339-340). Section 8 rules it a Phase 1 naming
error and specifies its M4 replacement.

7.5 Saturation boundary. semantic.md 2.4 says only `:gensym` prefix
and `:stream-make` buffer default. The executable loader also treats
an absent `:call` `:yin.code/tail?` as false
(`semantic.cljc:576-580`); Phase 1 materializes that false in its
vector (`ucf.cljc:77-78`). This is existing `v2` behavior, not a new
revision. The semantic.md 2.4 index omits that default; this history
corrects the referent without moving the operand's home. Conversely,
UCF 7.3.2 and `ucf.cljc:77` mention a default of zero for an absent
`:ffi-call` argc, but semantic.md 2.6 rule 7 and `code.cljc`'s
`:negative-argc` reject such a batch first. The fallback is
unreachable for well-formed `v2` input. The Phase 1 reviewer flagged
both the dead FFI default and a separate falsy-prefix discrepancy:
the loader defaults `false` to `"id"`, while canonicalization refuses
it. M4 must match the loader or narrow the claimed equivalence law
before relying on that edge case for lift.

## 8. Owner ruling: static kinds, dynamic reasons

Choose Option B. A static entry describes where an instruction may
park, not why this activation parked. UCF 7.4.1 distinguishes sent
from retained FFI calls by the outcome of a stream operation. An
effectful `:call` can yield different blocking effects at the same pc.
Neither fact is a function of the canonical vector. UCF 7.4.2 puts
dynamic state in the frame, and its static map does not specify a
`:yin.safepoint/reasons` field. The Phase 1 field exceeds that map.

The reference wait set currently mints `:next` and `:put` only
(`semantic.cljc:113,167,171,450`; `engine.cljc:383`). An explicit
`:park` is a parked record, not a wait entry with `:reason :park`.
FFI response waits use `:next`; retained requests can wait on `:put`.
An effectful `:call` parks according to the effect it produces. Thus
the current static vectors in `ucf.cljc:265-273` are design labels,
not observed reasons. They must not be copied into `:yin.k/reason`.

M4 shall replace `:yin.safepoint/reasons` with
`:yin.safepoint/kinds`, whose values are static possibilities from
UCF 7.4.1. Use these names, one per table row:

    :park        -> [:explicit-park]
    :stream-next -> [:blocked-read]
    :stream-put  -> [:blocked-write]
    :ffi-call    -> [:ffi-sent :ffi-retained]
    :call        -> [:effectful-call]

The two FFI values name possible outcomes, not two entries at a pc.
The `:call` value likewise does not select the effect or pending
variant. The static map stays a pure function of the vector. This is
a naming correction to the Phase 1 safepoint API, not a new `v2`
execution revision: `:yin.safepoint/reasons` was not a component named
by UCF 7.3.3 or specified in UCF 7.4.2. Consumers must migrate the
field when M4 implements the lift driver; Phase 1 has no runtime
consumer, though `ucf_test.cljc` asserts its current values.

The lift driver must check the pc against the static safepoint set,
then inspect the actual parked record, wait entry, and effect outcome
to construct `:yin.k/reason` and the corresponding UCF 7.4.3
`:yin.k/pending` variant. It may translate `:next`/`:put` where their
pending data suffices, but may not infer a reason from a kind alone.
For an effectful `:call`, the observed stream effect determines
`:next` or `:put`; the instruction's kind does not force
`:call-effect`. For FFI, the sent or retained state determines
`:ffi` or `:ffi-request` on the wire, even though the local wait
entry is `:next` or `:put`. Export must refuse when evidence is
insufficient. This ruling does not change the reference wait-set
classifier or mint new wait-entry reasons.

UCF 7.4.1 lists `:park` and `:call-effect` in its frame enum, while
7.4.3's claimed exhaustive pending examples show neither variant.
M4 must reconcile those rows before exporting them: an explicit park
needs a specified no-wait representation, and any use of
`:call-effect` needs a portable pending shape. This is a UCF protocol
gap, not grounds to invent a dynamic reason from static code.

UCF 7.11's safepoint harness must run each 7.4.1 row, including both
FFI outcomes and effectful calls. It checks static kind membership,
the observed reason and pending variant, and lift/lower parity. The
Phase 1 reviewer and architect identified this as a Phase 2 gate:
`collab/yin-ucf/1790268690622-reviewer-ucf-phase1.glm-flash.findings.md`
and `collab/yin-ucf/1790280923723-architect-ucf-phase1-signoff.gpt-6-sol.stdout.log`.

I-6. Phase 8 cutover records (2026-10-15; slices 8d, 8f-1, 8f-3 and
the documentation slice 8g, per the phase-8 plan
`collab/yin-vm/1792560000000-architect-phase8-plan.report.md`). Three facts
this history owes its reader.

First, the old transport **sources** died in 8f-1, not 8d. 8d removed
the old-codec tests (16 files, 141 deftests) and fixtures; the sources
`src/cljc/yin/vm/ucf/handoff.cljc` (3783 lines, codec versions 0-2,
`export-task`/`resume-task`) and `src/cljc/yin/vm/ucf/checkpoint.cljc`
were retained past 8d only because one other-seat C4 test still called
`handoff/export-task`, and were deleted in 8f-1 (Architect ruling R2/R3,
`collab/yin-vm/1792050511876-architect-8f1-ruling.report.md`) together with
`yin.vm.completion`. With them the version-0/1/2 wire bytes are
unreadable in-tree by design; the row carrier of body version 3
(`yin.vm.ucf-transport-tuples.md`) is the one carrier, and this
document's version-0/1/2 entries are the published description of those
historical contracts.

Also recorded here, so the fixture history is not silent: 8c regenerated
`test/resources/yin/vm/ucf/rows-checkpoint-v3.txt` (a phase 7a/7b fixture) although
the plan expected it to stay. The only content difference is its `installs` base
pack: the install child carried a `{:yin.code/contract "v3"}` stamp and now carries
`"v4"`, so three child segment addresses moved; no format keyword, lowering
profile, H, R or descriptor is in any pack. A moved for exactly the one fixture
whose child was still a v3 image (Architect final review of `srvm-phase8`).

Second, the `"v3"` canonicalizer and the stack-shaped safepoint halves
of `yin.vm.ucf` died in 8f-3, un-ported (8f-1 ruling R2, deferred
step 4b; commit `7b8d2a93`): no producer
of the v3 batch survived 8e, and the register machine's validator,
safepoint facts and frame validator live in `yin.vm.semantic.code`,
`yin.vm.semantic` §4 and `yin.vm.semantic.frame`. `yin.vm.ucf` is now
only `code-address`; the `v2` stamp carrier this document's header
names (`ucf.cljc:38`) is gone with it, so this file is the authority
for the historical stamps. `"v4"` (`yin.vm.semantic.code/contract`) is
the one semantic execution contract.

Third, the design document this history referred to as "semantic.md" is
deleted: at the 2026-10-15 cutover the frozen register-shaped design
took the file name `docs/design/yin.vm.semantic.md` (owner ruling Q6
(c), phase-8 plan) and the stack-shaped v3 design ("yin.vm.semantic on
dao.stream -- linear executable datoms", revisions 1-2, contracts
`"v2"` then `"v3"`) was deleted. Its revision 1 (2026-09-18; status
corrected 2026-09-25) published `"v2"`; revision 2 (2026-09-25)
published `"v3"`, adding `:define` and the reserved name `yin/def`
(the landed contract change is I-4 above). Its §1 stance was: everything
is a stream, with instruction fetch inside a loaded segment deliberately
excepted; interpretation creates semantics; code and state are datoms;
everything is a continuation. It enforced the six invariants: no hidden
global state, implicit control flow, callbacks, shared mutable state,
layer collapsing or assumed graphs. This stance and the revision dates
are preserved in the new document's [history block](./yin.vm.semantic.md#history). Citations of
its sections (2.2 the segment, 2.4 the opcode table and contract
index, 2.6 well-formedness, 3.x the stack-machine semantics, 5.3 the
flattening) are historical and resolve to this entry; the `"v4"`
vector, validator and transitions are the renamed design's §§2-3.

## 9. Source index

Design: `docs/design/yin.vm.universal-continuation-format.md` (7.3,
7.4.1-7.4.3, 7.11); the deleted v3 `yin.vm.semantic.md` (header, 2.2,
2.4, 7 pointer; its history now in the renamed
`docs/design/yin.vm.semantic.md`'s history block);
`docs/design/yin.vm.code-as-tuples.md` (4.1, 7.2);
`docs/design/dao.jing.md` (open items); `docs/design/yin.vm.linker.md`
(5.2, 9, 11 item 12, 12).

Collab: `1790243232166-vm-engineer-ucf-phase1.prompt.md` (phase 1
brief); `1790268690622-reviewer-ucf-phase1.glm-flash.findings.md`;
`1790280923723-architect-ucf-phase1-signoff.gpt-6-sol.stdout.log`;
`1790320431837-architect-linker-m2-gate.gpt-6-sol.stdout.log`;
`1790320567120-compiler-engineer-ucf-revisions.prompt.md` (this
task's brief).

Commits: 27fc0f9a, 394940e6, 2ce4451d, 3325815d, 3e987123,
ae76fe0d, b82813d8, 66d55abd, a76674a5, 0aa710e7, f05599f7,
3ddaa21b, e149aa31 (merge), f51077f2, c2b110a6 (merge), 1f7990d5.

I-7. G1 / L-d-1b (2026-10-16): append one required boolean `:yin.k/try?` slot to each `:link-request`, `:link-response` and `:install` pending row, preserving each waiter's return contract; regenerate `rows-checkpoint-v3.txt` via `rows-checkpoint-fixtures/render`: only `installs` and its three derived cases (`child-header`, `install-without-entry`, `child-op-id-out-of-range`) re-mint, with no code address or descriptor change.

Code: `src/cljc/yin/vm/ucf.cljc` (38-47, 265-273, 339-351);
`src/cljc/yin/vm/semantic.cljc` (113, 167, 171, 450, 605-616, 713);
`src/cljc/yin/vm/engine.cljc` (375-395);
`src/cljc/dao/space/query.cljc` (153-158, cited by UCF 7.3.2).

**2026-10-16, I-8 (module-pins, completed in module-pins commit 2).**
The required `requires` maps are `:yin.k/modules {module-symbol
address-keyword-or-nil}` and `:yin.k/host-exports {qualified-symbol
profile-keyword}`, always emitted, including `{}`. A missing key or malformed
map refuses `:yin.k/undecodable :requires`. The r2d correction carries the
entry's own `(:address entry)` keyword unchanged: Part B's string-or-nil
wording was a typing slip, not a request for conversion. Receiver equality
is `=` on those keywords (nil for host entries), and on used host export
profile keywords. Only live names answered by the registry contribute pins.
Missing host profile at lift is `:yin.k/non-portable :host-export-profile`.

After custody and before any attachment, `check-modules!` checks the entire
task tree: terminal `:module-pin-mismatch` and `:host-export-mismatch` precede
one `:yin.k/unsatisfied`, `:yin.k/discovery :complete` with the collected
`:yin.k/missing` maps, including all unadmitted carried install children.
Receiver `:yin.k/admitted-modules` defaults empty. The lift never carries
sender `:yin.admit/*` fields; a carried one refuses `:install-response`.
The holder driver forwards the composition's admission map on both lower
paths. No fetch, clock or lease enters the lower. No compatibility promise.

Re-mint verified 2026-10-16: all 53 checkpoint fixture packs compared against
the pre-slice packs' raw CBOR, saved before any edit. Removing only the two
added task `requires` keys and reversing the resulting content hashes and
row ordering reproduces every old pack's canonical CBOR exactly. The
frame-root fixture has no task and is unchanged. All 19 changed inline
rows-test task-root fixtures reproduce their original raw CBOR after the same
removal (captured using the original code and fixture constructors);
the frame-only root is unchanged. The ledger's four distinct embedded
checkpoint packs pass the same raw CBOR comparison. Running the original
rows code reproduces the saved old ledger text exactly; the new ledger
render reproduces the re-minted ledger text exactly. Only checkpoint/root
references and the projection digest consequently change in its journal.
No A address, descriptor, scanner pin or non-rows golden changed.

Old → new addresses (checkpoint fixture addresses are pack-byte addresses;
rows-test entries are task-row roots; ledger entries are pack-byte addresses):

| Fixture | Old address | New address |
|---|---|---|
| first-park | `:segment/blake3-55e427452e8f4391132710f286ca7487489cad4e141a57d4ef0e00fc6cc3211b` | `:segment/blake3-6476ee9f3b8034b8f4531e7a4c8752aaa22252b0c71eef4225ed54d6e551d981` |
| successor | `:segment/blake3-a19cc7890946ecf85620c15cfda10bcb22e91b26a16da411980a1609f41b0be0` | `:segment/blake3-ac85f7f3d7585ec3953d21e9216dd8ed09e969efaf1bad8bf7766cc13047e489` |
| installs | `:segment/blake3-d56bfa712ecfb2c8a6115b854f5ce48648468e8dcefd9096dbeadfbb31b15164` | `:segment/blake3-bbbb24b956178c437ede56b0fed6b5e5a516db3e9a0df3db32e4485f64c8cbbe` |
| parked | `:segment/blake3-90df74e86d05da24b07123ec7a9919a0d84d7519d095181c793686c6d56d350f` | `:segment/blake3-e6760ffc91709f87c5163cfd3b1a80a3ccabb148d841ac95fb02d37e4432d363` |
| halted-root | `:segment/blake3-1911589f713b74cd32fb7f6e5b8b5830c1d8029220e385562a48eeda905ff32e` | `:segment/blake3-427db2c1d2df33061226a34219cb991881f30a12e0bcac471b7c9acf3aecea1d` |
| variant-equal | `:segment/blake3-02512ba47f2df5daea9209eb3473337763fed9fde012bde2b91d3bc08448bd95` | `:segment/blake3-bc5ee607d275e28660a32176f290bbd2f50c3b44e9a2bc26c166d860248b7391` |
| variant-other-root | `:segment/blake3-8357394ab695d9c37d260ca0e834fef28d0a5b92403a1fe7b5bf62f11c2dcb1f` | `:segment/blake3-2692dc553d914382df0a73d6806948c685598e121b94bef7612935abb00c6182` |
| variant-different-intent | `:segment/blake3-8a70333955e8c5f326cc7bb5b6c83d72f4528223922202fdd0a6e92ecd4dc657` | `:segment/blake3-aa96eae24e5e570cad87de9439743364ff3a20424bcf57bc3dc06b3a4ee29bda` |
| variant-counter | `:segment/blake3-a3dba39e660b95fde78d201e4523d2b53178b5bb2ea4b8e7eb92da25c985a716` | `:segment/blake3-9f0d16561ddd5a18307443dd603ebf1ba2784fd4952a51a0ac1a1f0ef61bdc8c` |
| counter-at-bound | `:segment/blake3-c95edb32791a8749d85f1a2f3a442e2b8be997d7bd00e2612c1dedea3188c24f` | `:segment/blake3-7d6a86a9b5d32c2de15324d3cad7384ee28d731c0d1e5101506b6fedef1a937e` |
| hash-mismatch | `:segment/blake3-55e427452e8f4391132710f286ca7487489cad4e141a57d4ef0e00fc6cc3211b` | `:segment/blake3-6476ee9f3b8034b8f4531e7a4c8752aaa22252b0c71eef4225ed54d6e551d981` |
| non-canonical | `:segment/blake3-e8c53ff38a9b2e071704d02c29f381a9efad48b11c1d01010ee3930bb982a771` | `:segment/blake3-4463e32bca3eac46635faba855fa09e08c2ba1bd7b946b055b6442c073fe559d` |
| version-float | `:segment/blake3-2d35a52d211b7bb52d047a7d21453315e7e19a28822eb1f4041624e424fb48af` | `:segment/blake3-0fd10170d3fe436cc62c6739b9a7c577ba5a28867c65a62f18d8b17efa0afed8` |
| version-2 | `:segment/blake3-ebf0b7a50c756ee4790521c9e3757731e82ff622cc4187d0c2f02087f9016750` | `:segment/blake3-e004de28a608b1710d4e6c8056c9e59b88bd41202182626f0e90a54b6439692a` |
| version-absent | `:segment/blake3-663dc955a8754897e577b1c70e61b22deb735d555b08c762ee4ed8373a319f6d` | `:segment/blake3-676b1c7674c9ff3297187ebcc46fcd23c49fd74e5ba20ac4d0ef1f6f2a1f64a4` |
| no-tag | `:segment/blake3-471e0603a07e42cb01db177b418d888c0a7755250ea806ff04c67585fc9bb12e` | `:segment/blake3-9fcc1c1ac0ee00d105c0a4c77f7e62b1f88c5e20e2cfd463be4e8e73c4267809` |
| frame-root | `:segment/blake3-d60d5fe8229761d216bd11758cca433ae1d638f3e4d7d44ba5ee0a837c2aa392` | `:segment/blake3-d60d5fe8229761d216bd11758cca433ae1d638f3e4d7d44ba5ee0a837c2aa392` |
| row-tampered | `:segment/blake3-775034b7ae08c4b725da3ea1e1eeee11c2fae5017a1cc6422765436ab9fc3199` | `:segment/blake3-4665386a5d0f5bca39efc57b02ca7075e5e16db0c8e6fd121afcee5020fbbf3d` |
| bad-kind | `:segment/blake3-6f8190d4565a15a1db5a9d4d4db63d7da15ede48e752369a7e72f1cc014b1a8a` | `:segment/blake3-a6591ea6716950f704e9536febcfeff03d6d34ea16d3bc680344167d3e4fafa9` |
| root-role | `:segment/blake3-a47370033957e1f639dcb888d35365f4039db08310eed366614aed11f01f09a8` | `:segment/blake3-e59c072ad11e22e024be7aa628ccd7dfaf42855c39863a47289c1168298aef04` |
| missing-policy | `:segment/blake3-813ae292f57519863150d1fd5d354c236aece37f7d191ad17d48931e2229d8e3` | `:segment/blake3-b282683f5cf1f820a2a326dd47e3e49dd552631eca0f2bcf04f193edb86d059d` |
| missing-occurrence | `:segment/blake3-8b54d8d60cb0f7f2df360517182e5e61d11fc1b33fe600ad51959a77d91257c1` | `:segment/blake3-fb354867cd646c3bb9bb6077582177e731ea74a5549556100534ef761ef61dec` |
| missing-arbitration | `:segment/blake3-4c41df1a0500802c8270e9994ebe9190d238f02614d46241a065da557391057c` | `:segment/blake3-89276d1ea175cfab23ae2454821e732783bb7ef25ec1560913e11bdcf321528e` |
| missing-next-op-seq | `:segment/blake3-cc9428530949b213bd5c947d90fc0a804f8de7ebc1579b66a019618cce94c1e6` | `:segment/blake3-9aa007fc106d5e984b961c25df93ed4890d37205a72be38ee8fcc3ca6bf8a6f0` |
| fork-policy | `:segment/blake3-a6262e4b3b81e6a7a08c63be8ec1264bbd16d69fe9e4baca6d607adc4fd62316` | `:segment/blake3-4ed3ab0f9183ce6c973bc9183f8d6c671d31c1f00e033628740fd135fab99546` |
| nil-occurrence | `:segment/blake3-1de2d61eb70cfda2a5a1d36ad121548bb32e97cbbc049b0c07f1c2ea4c81312a` | `:segment/blake3-a7ce75bc7b9c2ef36086cd2f346a5135b26fb5a21c16e818db14dbb9174f1f85` |
| occurrence-not-uuid | `:segment/blake3-1a284645645aa5e47fdc4c63eeb2a012f3ec2f10ded7a94547c31464929747cb` | `:segment/blake3-2938ebf530d9abc35f634620bf3bd02664b5c6d2ec08869e56ff9309fd26b509` |
| origin-occurrence-not-uuid | `:segment/blake3-14a869bf4a4e7032a30a3d8b19d7ca382cbbfda6b1acd045cf008059ae4a3574` | `:segment/blake3-ce5ad385d97a680093535bee9470b7bd994ce35cac1b54431274beccaaff9182` |
| malformed-arbitration | `:segment/blake3-53cdc552922f73a1b9ee4700b54e644a95fb9af5a3375dcf6e11d5c2e19c5669` | `:segment/blake3-2a9d7c820735b14b307658065ece189e518c6a8c79d18db7a40b32a1baeccbea` |
| origin-is-self | `:segment/blake3-be847f1643893b65a0fc4f413d4c89386433f9d28efaca48feda2b7b1099e0a4` | `:segment/blake3-31e012e8fb81b41514d6d4dc2a661737614da2d6b812c3935f8f3e986fb33d1c` |
| malformed-origin | `:segment/blake3-7db08d797446b1629c98254ec689639edf951e6bbb948eb25da9b2f3a4c1074e` | `:segment/blake3-31f4302bebe6f54d265fbbac8d6efe2bdd7ca9e0260f27e5e30052b37499a319` |
| origin-nil-emitter | `:segment/blake3-f92492587860214c5a6d15d3721a1659c01cd44dc58a45976967ce38b79059fb` | `:segment/blake3-73cddfb255701b36548eb698b7ac57f80bac8c437db1c7d3d5ea7db7c34e277a` |
| counter-float | `:segment/blake3-8624e221a2a6b17c0aea705bf2b99be1e934bf0722df89464b7714d4ad6902d3` | `:segment/blake3-61a7cf82087568a91708d24b21ca9b37257a1ff6f43f1c92bdcc1990e33076a7` |
| counter-over-bound | `:segment/blake3-908edad2d31f2426ad8122f0e8e5f938b80d949c9b2998c49eaee12685522230` | `:segment/blake3-ca8fa4b0ccfb8576a66b4a0439b470eda9da0e43c80e7bf846b7a76e38e1883d` |
| counter-negative | `:segment/blake3-02b6ba158117d2a2c0696d84e4c0e700c6b8c225d33dec6771d1e77fd993afb9` | `:segment/blake3-0fe84ffe9980965c0901a0fe05a6a937420365704ef5c90f3df7640495f32eab` |
| first-export-counter | `:segment/blake3-3d0f52f46a637bc71f9370f153d1d3081974790dfc34642af1ef698532e414bb` | `:segment/blake3-6dba44e9e9efc3aa5d4a4ebd1236ac17f9120c9f836c5b3dd26a5efd40439895` |
| halted-root-header | `:segment/blake3-320e1d8b88f033a73096545a64c4fa74c1fe5cbd6dbc9c2cdaebee0f194ebca2` | `:segment/blake3-1da4c1ba17674e6b825a7a0f062471f458c3a2ecd69f712d296e78ca3012679c` |
| halted-root-without-origin | `:segment/blake3-8fa2b7652faabf26893499b483d0b94c2e50950faadd6a31e095fa2acf6fd1a8` | `:segment/blake3-ad0a5553f535dedd8c01081a8c2283319b5962713e376caf71970393f64462bf` |
| custody-epoch | `:segment/blake3-401d2721701bedc4033bed80647062a0592958232a47d55370b38ef7453b7abc` | `:segment/blake3-a392fbecad7b253e0a0690dc0a42c0a2ab10e47185b30283f532e75b799f3095` |
| custody-lease | `:segment/blake3-9aef98779c919e4c1a41f5278e77be7e47b133b11f6ac83087d79307becbfcd5` | `:segment/blake3-74466266e6b39b9bace03ba63d5711d728224196991741216ffaf0f6bc2981c1` |
| custody-not-map | `:segment/blake3-3d29f409c18d892f0b553df9ab950460e11a8bdc151a7972f7b3acce1ec6bce4` | `:segment/blake3-789f4de876127be820a3cd1525241d3825dc3aba2909e2d985b33aeac2725a39` |
| child-header | `:segment/blake3-767ec0379e4ba4d8885b7e956c241fff73fac495cbe6a2db5826ae1d4dda13d9` | `:segment/blake3-ef296b519b90a97fb5154b2781cd0618748ced980b23ed5a2632ba6feac8674d` |
| install-without-entry | `:segment/blake3-d8786a650510c9ae020a81924bc2540ea9cf0a0327222a459440060da317ea09` | `:segment/blake3-0de4bc93c7a2e44a34b3235d24cfa77306b8a7d4b66b8e3212e43b89d808e509` |
| op-id-at-counter | `:segment/blake3-66e184533fe4a1b5bc63095c3133b10aaba5684131f1b60f2f9dab1fbda5caed` | `:segment/blake3-84e361819cd79a0c4c640d534c510584345872f54456137efe3405bc4ba26f3a` |
| child-op-id-out-of-range | `:segment/blake3-32de289b7ed1ae55c3ade7283cb67474e25d377f41a1f446e4832d05811772a4` | `:segment/blake3-000ac0472b6280cbb9bc5bcd96330e203bea6fa15d1718ccec6dd110cd2fdf30` |
| duplicate-op-id | `:segment/blake3-f207ceb70ad9e6250d0c1df8592db375b93e0c7e3c6c3210bcb67200fe4d1aed` | `:segment/blake3-2872ed1e74bb0e34eeab876a99e7f8e574053d69afc8f1dc2b347ee30c4df93a` |
| op-id-without-origin | `:segment/blake3-8964674b8260b15843e65fe17947e0bef2b2221584dc0a6471dca40fc216a80c` | `:segment/blake3-0d44ef0b0b10bb0ce136e45bf44bd3d8c57f48fb669755593eb556902a39afdf` |
| op-id-own-occurrence | `:segment/blake3-cdae47e4df1f0ad8347161057dd9408d260b2a598b599530a4c70a8d95081a15` | `:segment/blake3-0a11203ede96db6d91450ba13a6dd8dd11922f742b17efb18d0f1ececc3f5441` |
| op-id-malformed | `:segment/blake3-a75534bc61036b0b4660ad76483a32cd6373b3211db902e43ee3762023c5ea4b` | `:segment/blake3-f8aa256eadf2e7040780a2872334486bf21d1e2aa3f12df4bec395ee73d0885b` |
| op-id-occurrence-not-uuid | `:segment/blake3-c92cd49224a69e03b3c1399ab258a59916c9fb8cb84f975ee94cb0fe71b75069` | `:segment/blake3-78be5e825f451a7ca04717aa572c92b4ee9f6e077763cb82c1cc927f3161ce28` |
| op-id-seq-float | `:segment/blake3-31c112cf188e5208263df2ce755185abb091ae9f25468ec0b553a09224828930` | `:segment/blake3-4832dae2785e0aa69429450d112d0883a0894380767a61128c9b7e560b375bba` |
| op-id-seq-negative | `:segment/blake3-182561edcfc65efc48a78458608325dbb378559added6e7178d69ccd05d8f374` | `:segment/blake3-4a5dba255318f14e7dfae3fbf47d7bfb1078e102e53af89cd7fc8b2f86e21835` |
| fork-op-id | `:segment/blake3-a02c26dc08f351487043a5292ad5f0be276ef44a123fce9ae9f24a308bd5e328` | `:segment/blake3-dcb0611b00a3a1df54c0cd9925574bca90f4e7bd12326787378477eeb4e50d80` |
| rows-test/canonical-roots-are-pinned #1 | `:segment/blake3-fab223d9b3540fdce0ccf2132f8a4041917a158a32295470a4936754742d3ac4` | `:segment/blake3-01c05feeff35291a595f1ff65b4cb912bc7c9ca8acf68d8164b88848053cbe2e` |
| rows-test/canonical-roots-are-pinned #2 | `:segment/blake3-a850fce6317b426090fa65758805c26b3628efe75d16d507095563c1608ab533` | `:segment/blake3-c50089d09d1fd203d23369b225979449fde4a2aeae511eb74fe76c3625c7e80c` |
| rows-test/canonical-roots-are-pinned #3 | `:segment/blake3-4993b5b192de7d903a90f7bc87dde8c7d5ac007c2b013ef62b998dc988282576` | `:segment/blake3-e5c8c82e6079df50b884cbac278d1134aa1c8dd587e9c539bdade7fc93e6669c` |
| rows-test/canonical-roots-are-pinned #4 | `:segment/blake3-536c810b07c7cab1dd812fcb1bbf0c7433dc41af4e39037c5f278e82abf1e032` | `:segment/blake3-e1de5400ed3aca2f8b68390df524135b12ac65deb7be6d18bf324b17ab145c95` |
| rows-test/canonical-roots-are-pinned #5 | `:segment/blake3-e67405ecd326c239a84e29a50ca53dfe503fa6baf20c5b79e685c878a40257d7` | `:segment/blake3-525ba654dc2cc3575abc81b70f399b18b8808c410bbb67bf0f3565ce4d581e14` |
| rows-test/canonical-roots-are-pinned #6 | `:segment/blake3-6e7c7a6b0674c41c21e33d9630d4aaf7fc92e4b30234a9c2a1b8c7bae7792f88` | `:segment/blake3-18ae5ebc90b121ce3ff21c08f61d18b6fb287369b77ac2ab67a3032e0fd744e6` |
| rows-test/discovery-order-numbering #1 | `:segment/blake3-0b9ef61ebd08535b10e136a6452d942fb0598ad2dd5a13665d7756fac69702b5` | `:segment/blake3-e209ab0e6539b30cbfedca526693c7591cb6e96a243252b54a112717a7b0061b` |
| rows-test/discovery-order-numbering #2 | `:segment/blake3-ff124bfc1fd2e6c2b8ce29487204b442b8c1143f71339ccab5efd6e48ccc142f` | `:segment/blake3-137d7203380b85bc7d555951225f653c6bcbc577900fffbd32ac6983f1fae456` |
| rows-test/occurrence-count-separates-members #1 | `:segment/blake3-2d9873a8f860440d056768d02172a59f28856f804677a5ac0eaba8a8b6edbcf7` | `:segment/blake3-8d0639824c8f9cb6dc5ba6a6b7f15e3ab840d15ac003ddd97ad3f4e06814081a` |
| rows-test/occurrence-count-separates-members #2 | `:segment/blake3-726a9fbb35cfe76b6d3caf63c5f7e4ce61d4e73d34efda9c3674eb73db830da1` | `:segment/blake3-2dfc65d2d06637ce2a99732aeefc6634c8831e39a993e98eeb75f3015ae3f444` |
| rows-test/occurrence-count-separates-members #3 | `:segment/blake3-d84480d4d7e186937a9165be103f7cfaf0d3f4ced83ff65f5be3e906a48107a2` | `:segment/blake3-6dc110420a7be0afe3d725f4fa879465321114a7be4777c19cfd6225ac84969b` |
| rows-test/interchangeable-ties #1 | `:segment/blake3-07eda35d34844aa8e1ce89f07395bd877a49e7a69bdabea55bcf22e600671494` | `:segment/blake3-6eff99968d148843d4354e207cb25d302e175654ddc57c1b6d4ebed0aef544de` |
| rows-test/interchangeable-ties #2 | `:segment/blake3-da69c679d10e5356b17cdb24909a43d7475df9114bba9c09a8f98ac1a35b971e` | `:segment/blake3-284223c34bef7b455856c0f946dbbac2671e859dafbd210089ab04dbbd0134a2` |
| rows-test/interchangeable-ties #3 | `:segment/blake3-49d4478ce74e89a973c9640498342dfea85867eff1662b9656f7e6b9bcd44e93` | `:segment/blake3-67dfa215ee3c1cbc0c9a14cf1dc82a82e4aae2f672950d70d83b52c239f520b5` |
| rows-test/interchangeable-ties #4 | `:segment/blake3-18b70de6d4533d5ca797464568b5dd7812ca4b2f36a1e8fb64ef823a2eb3da45` | `:segment/blake3-ff4ab85017768eb2b7bf6347d03e1bb4aeb0d167ff716adcadf1b43cb54b31ae` |
| rows-test/alias-pattern-is-part-of-the-key #1 | `:segment/blake3-387f294a2f1963f2f2241e8527d8698dedff5d50de4f46bb762b456a01375090` | `:segment/blake3-8cc2defe531a8581d4b15f7fabb28aeb9bbacaa16a15bb5f6cba13cdd9643fea` |
| rows-test/alias-pattern-is-part-of-the-key #2 | `:segment/blake3-be62ba43d9d07dc7b5e29375e195ab839ab37b721074af816e540615f6167ee9` | `:segment/blake3-10d92587620fd86b56ccbfd10348c69ebc06903f00afb87772b28d2ce180f42a` |
| rows-test/alias-pattern-is-part-of-the-key #3 | `:segment/blake3-5376f133e83957e727611ee977a7b565e8e3c6b41a916f7a1857ecaf84f83907` | `:segment/blake3-b20e118772567652309a63ace21fb24ea76dd089e389a844d78bb840728eee51` |
| rows-test/alias-pattern-is-part-of-the-key #4 | `:segment/blake3-0193312113d9c4928b148f220ef82a623c397e1b98cd67fac8712d1fc286a032` | `:segment/blake3-415b8a3bebf6b0b21bb054f31c4f6a1bc5e0fc1613605b77addd00f6fd935aef` |
| ledger checkpoint 1 | `:segment/blake3-860855f35aae9d61ca1b7ce21ed7c6cacb5fb7d536b5f363d42f604a694b74fa` | `:segment/blake3-e674be239b6dd62e5b3b279b43d11d7d5fe5d853fe93becb92a8a71a6d6399f7` |
| ledger checkpoint 2 | `:segment/blake3-f3c7c3c2d3c9c7c64b10b4cd46f711df1befd76bd366f03a40cc157db3ce0d34` | `:segment/blake3-5e240ede63c00f163d700e431c4e09762428f1b0de4ed48e5fea0fbf50b90d92` |
| ledger checkpoint 3 | `:segment/blake3-c31a1847cd8466aafe6220fca1972b72924a6c69eef3e1260970619ec80d787d` | `:segment/blake3-51ca5000cf870c9bd7c2c284dc8a8a38734ef48fc3e3ebd48fe525943d34bfb9` |
| ledger checkpoint 4 | `:segment/blake3-ab97dc5502245c63ab49b45088b88989fa143616cadf6e298b8ae90d9694d711` | `:segment/blake3-42b4357517e46880bcf804e32657f483088d0b5821b36249cdd38aa110afb79e` |

Ledger projection digest: `2b26e75e8969428761c4f7813d1ffc886d30d160f2f3e8f80076bbe24ef1f227` → `419e3aa652d79cab6921cf7a6c02972bcfe2d3b88ba472bc03ac8f1a935f27d2`.

**I-9 — 2026-10-16, any-PC.** The current lift rejects active control and queued completed outcomes, while the holder runs unrestricted computation. Slices 6d-a, 6d-b and 6e extend the carrier to run states and complete scheduler products. 6d-a includes completed stream/FFI/link/install outcomes, cursor-update encoding, bounded bridge-aware fuel and closed-gate execution guards. 6d-b adds explicit state retention on tenure failure and holder migration from fuel boundaries. 6e extends backend layouts and transfer laws. Earlier static-safepoint-only lift prescriptions are historical. No compatibility promise is added.
