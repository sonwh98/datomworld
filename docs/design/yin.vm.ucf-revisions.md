# The canonical instruction vector contract -- revision history

Revision: 1 (2026-09-25) -- architecture-owner publication of the
history and the static safepoint-kind ruling in section 8.

Status: authoritative revision history for the `v2` stamp in
`src/cljc/yin/vm/ucf.cljc:38`. It completes the publication indexed by
`yin.vm.semantic.md` section 2.4 ("semantic.md" below), closing the
documentation part of UCF section 7.11's contract-revision-publication
blocker and the Phase 1 sign-off's P2 publication gate. This ruling does
not accept UCF as a whole; UCF 7.11's other gates and tests remain.

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
(`collab/1789387292000-architect-ucf-review.gpt-6-astra.findings.md`,
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
  (`collab/1790268690622-reviewer-ucf-phase1.glm-flash.findings.md`),
  including the `:reasons` finding (section 8 here) and the
  saturation nil-vs-truthiness P3 at ucf.cljc:171.
- Architect verdict GRANTED with Phase 2 gates
  (`collab/1790280923723-architect-ucf-phase1-signoff.gpt-6-sol.stdout.log`):
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
(`collab/1790320431837-architect-linker-m2-gate.gpt-6-sol.stdout.log`,
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
     snapshots, the one cell table, closed requirements (cursor
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
  5. Cell identity discipline: cell ids are lift-local, minted once
     across the whole reachable graph (frames, waits, stores, closures,
     parked records); the lower allocates fresh resource keys per cell
     and re-seals every reference under the receiver.
  6. The cursor-profile claim: a body carrying cells must claim
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
`collab/1790268690622-reviewer-ucf-phase1.glm-flash.findings.md`
and `collab/1790280923723-architect-ucf-phase1-signoff.gpt-6-sol.stdout.log`.

## 9. Source index

Design: `docs/design/yin.vm.universal-continuation-format.md` (7.3,
7.4.1-7.4.3, 7.11); `docs/design/yin.vm.semantic.md` (header, 2.2,
2.4, 7 pointer); `docs/design/yin.vm.code-as-tuples.md` (4.1, 7.2);
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

Code: `src/cljc/yin/vm/ucf.cljc` (38-47, 265-273, 339-351);
`src/cljc/yin/vm/semantic.cljc` (113, 167, 171, 450, 605-616, 713);
`src/cljc/yin/vm/engine.cljc` (375-395);
`src/cljc/dao/space/query.cljc` (153-158, cited by UCF 7.3.2).
