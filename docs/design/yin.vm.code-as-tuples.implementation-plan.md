# yin.vm.code-as-tuples — implementation plan

Status: implementation plan for
[`yin.vm.code-as-tuples.md`](./yin.vm.code-as-tuples.md) (revision 8), the
flat-row grammar for code content and everything the design hangs on it:
the row codec, the row-fed loaders, the canonical instruction vector, the
lowering profile and derivation records, the ledger, occurrence identity,
dependency closure, and the macro batch protocol. Subordinate to that
document, to
[`yin.vm.semantic.md`](./yin.vm.semantic.md),
[`yin.vm.universal-continuation-format.md`](./yin.vm.universal-continuation-format.md)
(UCF), [`yin.vm.macro.md`](./yin.vm.macro.md), and
[`dao.jing.md`](./dao.jing.md), each of which owns rules this plan can
only schedule against, not change.

Drafted 2026-09-17, architect r1, against the working tree on the
`dao.stream-redesign-v2` branch at `1116497`. Line numbers are as of that
tree. The revision history at the end records what each round changed.

## The problem and context

The design is 2,094 lines and its own §10 lists fourteen acceptance
blockers and open items, most of them marked "design work with stated
mechanisms, not yet implementable" or "no design answer in this document."
The brief asked for a census of what exists against that list, a phasing by
real dependency, an honest split between what is buildable now and what
waits on a decision, and a plain answer to whether anything can start.

The brief's premise was that essentially nothing is built: no map↔rows
codec anywhere in `src/`, no expander, and a linearizer that serves the old
datom path. Two of those three are right. The first is wrong, and the
correction changes the shape of the plan: **the codec is the true bottom of
the dependency graph, and it already landed on 2026-09-16 with the round-trip
law as its own test.** What is unbuilt is everything *above* the codec on
the row side (no loader, lowering, validator, or query reads a row today)
and everything the design borrows from UCF and the macro contract, neither
of which has a line of implementation.

The plan therefore has a different texture from the three retirement plans
that preceded it. Those were mostly deletion with two design units each.
This one is mostly construction, and it divides cleanly into a third that is
mechanical against a pinned design, a third that is mechanical once one of
five named decisions is taken, and a third that is genuinely speculative
architecture (dependency closure, custody-grade provenance, the expander)
whose governing documents are themselves marked *Proposed / Deferred* with
"nothing below is implemented."

## What is verified and what is judged

Everything below is a verified fact from the 2026-09-17 sweep unless marked
**[J]** (judgment call) or **[B]** (a claim in the brief the sweep
corrected).

### What the brief got right

- **No macro expander exists.** `src/cljc/yin/vm/v2/` holds
  `ast_walker, code, engine, ffi, linearize, module, runtime_adapter,
  semantic, telemetry` and no `macro.cljc`; `grep` for `expand-batch`,
  `harvest`, `:incarnation` over `src/` returns nothing. `yin.vm.macro.md`
  never claims otherwise: its `macro.cljc` is a Phase 1 *deliverable*
  (`macro.md:946`), and its only existing artifact is a deleted v1 file
  recovered by commit id (`macro.md:600`, `d8b27a5^`).
- **`linearize/lower` serves the datom path.** `lower` takes `[e a v t m]`
  datoms and reads them through `vm/index-datoms` (`linearize.cljc:162-227`);
  `lower-ast` is `lower ∘ ast->datoms` (`:245-252`); `ast-loader` composes
  it in front of the semantic VM's datom loader (`:269-286`), chosen in
  `yin.repl.v2.core/program-loaders` (`repl/v2/core.cljc:62-68`). Nothing in
  it touches a row.
- **UCF has no implementation.** `:yin.k/`, `safepoint`, `dao.lease`,
  `:yin.code/contract`, `yin.ledger`, `yin.lower` — zero hits under `src/`
  and `test/`. `:yin.code/hash` exists only as a reserved schema entry
  (`v2.cljc:353`). UCF says so itself (`ucf.md:32-35`: "Nothing below is
  implemented").

### What the brief got wrong

**[B] The row codec exists, is tested, and the round-trip law is already a
passing test — item 13 is done, not "testable now."**
`yin.vm.v2/ast->semantic-bytecode` and `semantic-bytecode->ast` live at
`src/cljc/yin/vm/v2.cljc:590-826`, driven by `semantic-bytecode-grammar`
(`:590-610`, all 17 §2.3 tags) and `semantic-bytecode-defaults` (`:613-620`,
the §2.4 saturation table). They landed as `84f8eef` on 2026-09-16 after a
five-round adversarial cycle (`docs/orchestrator-log.md:3389-3473`), then
lost the `:global` tag in `c5cea20` (`:3510-3567`). `test/yin/vm/v2_test.cljc`
carries 15 `semantic-bytecode-*` deftests over a 29-program canonical corpus
that covers every tag (`:220-263`), including the numeric store key item 2
demands (`:243`); `semantic-bytecode-round-trip-law` (`:266-271`) asserts
both directions. `test/dao/space/query_test.cljc:1111-1320` runs Datalog
over the codec's real output. The design's own §6.1 names the function.
What the brief missed is exactly what a namespace grep for `codec` or
`rows` misses: the code lives in `yin.vm.v2` itself under the name the
design gave it.

**[B] What is missing on the row side is narrower and more specific than
"all unbuilt."** No `src/` namespace *consumes* the codec (`grep
semantic-bytecode src/` hits only `v2.cljc`). Concretely absent:

- a loader that takes `{:root :rows}` — the walker loads datoms through
  `datoms->ast` (`ast_walker.cljc:686-698`); the semantic VM loads
  `:yin.code/*` datoms through `load-image` (`semantic.cljc:524-596`);
- a lowering over rows — `lower` reads `get-attr e :yin/type`;
- the canonical instruction vector and its address — `load-image` decodes
  attribute maps into `[opcode & operands]` image tuples, but no function
  produces UCF §7.3.2's positional vector, hashes it, or decodes from it;
- the §7.4 validator beyond what changes a row's own address —
  `semantic-bytecode->ast` checks `:shape`, `:content-address`, `:tag`,
  `:arity`, `:saturation`, `:id-resolves`, and `:slot-kind` for `nodes`,
  `syms`, `bool` only (`v2.cljc:761-812`); `node` (is it an address?),
  `data`/`key` (`plain-data?`), `sym`, `kw`, `str`, `int` are unchecked, and
  `:root-reachable` does not exist. The architect sign-off of 2026-09-16
  called this the right unit boundary and named the rest "separate future
  validator work" (`orchestrator-log.md:3443-3446`);
- the §7.5 vector validator — `code/well-formed?` (`code.cljc:155-180`)
  judges datom batches with seven rules and has no `:operand-kind`,
  `:saturation`, or key-domain rule;
- the occurrence relation and its indexer — `query_test.cljc:1284-1287`
  builds the occurrence fixture by hand and says so;
- `project-segment`, the `$code` relation, and every §7.7 extraction query;
- the walker's `:stream/close` arm (§3.2) — the walker's `case` ends at
  `:stream/next` (`ast_walker.cljc:462-469`); the codec, linearizer, and
  semantic VM all carry it.

**[B] Two things the design still schedules have already happened.** The
nil-filling parameter binding of §7.7.2 is `engine/bind-params`
(`engine.cljc:46-51`), called from all four closure-application sites
(`ast_walker.cljc:192,513,555`, `semantic.cljc:200`). And the `dao.jing`
encoder fix §4.2 depends on is committed (`0cafb2d`), which the design's
§4.2/§10.1 now reflect after the 2026-09-16 correction.

**One fact about the governing documents the brief did not ask for and
matters more than any item:** `yin.vm.universal-continuation-format.md` is
**untracked in git** (`git status` shows `??`), as are
`yin.vm.v1-retirement.implementation-plan.md`, `agent.harness.md` and
`dao.jing.cbor.md`. Item 8 asks UCF to be amended; there is currently no
committed UCF to amend. `yin.vm.macro.md` is tracked; its header still says
"Revision 3" while its appendix records approval at revision 8
(`macro.md:10`, `:1298`).

### The other thing a careless reading gets wrong

The design's §7.1 load-path table says the walker "natively reads map AST
from stream topic without conversion", while §6.1 and §9.1 say the walker's
loader is `rows → map` reconstruction, "the successor of `datoms->ast`",
and §9.1 says frontends "keep emitting map ASTs to the stream" with all
projection "deferred to the Encoder Observer". Both are consequences of
the 2026-09-15 ruling that admitted the map AST as a stream topic
(`fbbab7d`). They are not contradictory *as code* — a walker can own both
loaders — but they are contradictory *as topology*: which medium the
walker attaches to decides whether the encoder observer sits between
frontend and evaluator or beside it. That is item 9's batch-shape question
in different clothes, and it is an owner decision (D2), not an
implementation detail.

## Census

### Built — exists, tested, no unit owed

| what | where | evidence |
|---|---|---|
| §2.3 grammar table, §2.4 defaults, §2.5 strip rules (symbol metadata, reader positions at every depth) | `v2.cljc:590-661` | `84f8eef`; 15 deftests |
| `map → rows` projection with structural sharing and a metadata-aware collision guard | `v2.cljc:692-743` | `semantic-bytecode-structural-sharing`, `-sharing-refuses-to-merge-distinct-metadata` |
| `rows → map` reconstruction with the address-affecting subset of §7.4 | `v2.cljc:746-826` | `semantic-bytecode-reconstruction-validates` |
| round-trip law (§7.2 part 1, item 13) over a canonical corpus of every tag | `v2_test.cljc:220-271` | passing |
| `q` over rows: per-tag arity, joins across arities, `:fns` predicates, recursive rules | `query_test.cljc:1111-1227` | passing; `query/relation` at `query.cljc:153` |
| the occurrence-joined free-name query on the mixed case, with the row-only contrast | `query_test.cljc:1230-1320` | passing; fixture relation, not root-scoped |
| §7.7.2 nil-fill binding | `engine.cljc:46-51` and four call sites | landed 2026-09-16 |
| `dao.jing/segment-key` over an order-normalized, collection-metadata-aware printer | `jing.cljc:271-310` | the three §4.2 pairs closed by `0cafb2d` |
| the observer prerequisite `yin.vm.macro.md:822-840` names (`run-on-stream` carries the partial session in the throw) | `observer.cljc:217-275` | docstring and implementation match the fix |

### Missing — implementable now against the pinned design

| what | design | unit |
|---|---|---|
| walker `:stream/close` arm and frame | §3.2 | U1 |
| full §7.4 row validator (`:slot-kind` for every kind, `:root-reachable`, explicit `:acyclic`), the malformed-row corpus | §7.3, §7.4, §7.2 part 3 | U2 |
| walker row loader `{:root :rows} → :program` | §6.1, §7.1, §9.1 "loader, new" | U3 |
| lowering over rows to the canonical positional vector plus the provenance table, origin passed opaque | §5.1, §5.3, §9.1 linearizer row, UCF §7.3.2 | U4 |
| §7.5 vector validator, positional decoder, address alias column, projection-path equality | §7.5, §7.1, §7.2 parts 2–4, UCF §7.3.4 | U5 |
| `project-segment`, `$code`, the syntactic §7.7 extraction queries, the footprint table as data, tree/segment requirement equality | §6.2, §7.7, §7.7.1 (item 6, syntactic half) | U6 |
| the occurrence relation emitter and root-scoped, grammar-generic occurrence rules; free-name extraction | §2.5, §4.5, §6.1 (item 5, extraction half) | U7 |

### Missing — mechanical once a named decision lands

| what | design | blocked on | unit |
|---|---|---|---|
| `"ast-v1"` profile as a published pinned document; `yin.vm.semantic.md` §2.4/§5.3 amendment (item 14) and its `zipmap` corrections | §5.2.1, item 7, item 14 | D1 | U8 |
| derivation records, two-step verification, ledger event entities, naming rows, provenance link from naming fact to event (item 10) | §5.2, §5.2.2, §8.1–8.3, §8.6 | D1, U8 | U9 |
| rows and vectors materialized in `dao.jing`; `:yin.code/hash` written | §2.1, §4.1, UCF §7.3.2 | D3, `dao.jing.md`'s metadata-carry open item | U10 |
| the observer row lane: batch shape, `program-loaders` switch, the program-input predicate, REPL eval path | §9.2, item 9 | D2 | U11 |

### Missing — design work still owed to another document or the owner

| what | design | who decides | unit |
|---|---|---|---|
| UCF §7.6.1's two amendments (item 8); UCF committed at all | §7.7.2, §7.7.3 | owner, as UCF's author of record | U12 |
| primitive-profile registry (item 11, UCF §7.11) | §7.7.2 | owner / UCF | U13 |
| conservative dependency completion over work items (item 5) | §7.7.2, §7.7.3 | after U12, U13 | U14 |
| the medium identity coordinate and the batch coordinate of `[:source medium batch j]` (item 3) | §2.5, §5.3, §8.4 | owner + `dao.stream.md` | D4 → U15 |
| the v2 expander (`macro.md` Phase 1), then the §8.5 batch protocol, the `:incarnation` field, the §8.4 event shape (items 3, 4) | §8.4, §8.4.1, §8.5 | owner (D5), `yin.vm.macro.md` | U15, U16 |

### Dissolved, inherited, or already satisfied — no unit

- **Item 12** dissolved by §4.3; nothing to build.
- **Item 1** inherited from `dao.jing.md`; the pinned encoding is that
  document's first open item and changes every address when it lands (UCF
  §7.3.2 accepts this). Not scheduled here; Phase 0 records the rule that
  no address minted before it is durable.
- **Item 2** is satisfied at the codec (`:key` typed `:key` in the grammar,
  `data`/`key` payloads stripped identically, a numeric key in the corpus)
  and becomes a criterion on U2, U5 and U6 rather than a unit.
- **Item 13** done (above).

## Decisions

### D1 — the lowering profile is published by copying, not by designing [J, owner signs]

Everything `"ast-v1"` must pin is already written in §5.2.1's table and is
already what `linearize/flatten-program` does: walker order, labels numbered
in emission order, bodies out of line after the main sequence in encounter
order, `:halt` and `:return` terminators. Item 7's "must be published as a
pinned document" is therefore a publication act, and item 14's amendment
to `yin.vm.semantic.md` is a documentation act. What is *not* mechanical:

- `yin.vm.semantic.md` has no revision line and no argument-binding rule at
  all; its §4.1 (`:430-432`) and §6.2 (`:627`) still say `zipmap`, which the
  code no longer does. The amendment must correct those two sites, not
  only add a paragraph to §2.4/§5.3. It should also record four drifts the
  sweep found: `well-formed?` runs seven rules to the doc's six
  (`instruction-shape` is undocumented, as is the ref-required-ness of
  `:jump`/`:branch-false`/`:closure`); the decoder's `"id"` and
  default-capacity saturations are undocumented; the
  `:current-continuation → :current-cont` alias is missing from §2.4's
  list; §2.4's `:call` row cites §4.3 for apply rules that live in §4.2.
- One profile question §4.3 leaves open by name: one segment per lambda
  versus bodies out of line in one segment. **Default:** `"ast-v1"` pins
  today's behaviour, bodies in one segment; per-lambda segments would be
  `"ast-v2"`, a different profile, never a revision of this one.
- The profile's target is the UCF §7.3.3 stamp `{:yin.code/contract "v2"
  :yin.k/version 0}`; UCF's own blocker 6 says the `"v2"` revision history
  must be written into `yin.vm.semantic.md` §2.4. U8 does both in one
  edit, because a profile targeting an unpublished contract cannot be
  verified either.

Owner action: approve the text U8 produces. No code waits on this except
U9; U4 emits the vector without a record and is not blocked.

### D2 — which medium the walker attaches to [owner]

Three consistent topologies exist and the design admits all three:

1. **Encoder in the path.** Frontend → map-AST medium → Encoder Observer
   projects to rows → row medium → both evaluators load rows (walker
   reconstructs, semantic lowers). Item 9's batch is one `{:root :rows}`
   value per tree; §7.1's walker row is wrong and should read "rows,
   reconstruct". This is what §1's "all `yin.vm` evaluators are `dao.stream`
   observers that load rows" says.
2. **Encoder beside the path.** Frontend → map-AST medium → walker loads
   maps directly; the Encoder Observer and the AST indexer observe the same
   medium and publish rows for the semantic VM, storage, and `q`. §7.1's
   walker row is right; §6.1's "loader" is the semantic side's only.
3. **Both loaders on every evaluator**, medium chosen per composition.

The code cost is the same in all three (U3 builds the row loader either
way; the map loader is `assoc :program ast`). What differs is where the
REPL wires the encoder, whether the walker can run a tree that was never
projected (and so never validated by §7.4), and whether the tests of §7.2
part 2 compare "rows direct vs datom projection" or "map direct vs rows
direct". **Default [J]:** topology 1, because §7.2's conformance obligation
is stated over rows on both paths and because an evaluator that runs
unprojected maps can execute a `:vm/store-update` node that no persistent
form carries — the exact hole §3.1 closes. Topology 2 is the one the r8
intro's "LISP using maps" ruling most naturally reads as; if the owner
means that, §7.2 part 2 must be restated and U11 wires the encoder as a
peer. U11 waits; U3 does not.

### D3 — how `dao.jing` stores a tree's rows [owner]

§2.1 names this an Open Question and it is absent from §10. Individual
rows (git-style: one address per node, shared subtrees shared across trees,
N fetches per load, `:root-reachable` checked against what was fetched) or
a pack per tree (one fetch, a pack address distinct from the root id, the
validator's reachability rule is over the pack). It decides the loader's
interface and it decides what `:yin.k/carried` carries. **Default [J]:**
individual rows as the stored form, with a pack as an *optional* transport
envelope that hashes to its own address and is verified row by row on
receipt; this keeps §4.4's cross-tree sharing real rather than nominal.
Gates U10 only.

A prerequisite U10 cannot dodge, from `dao.jing.md:447-464`: no backend or
wire codec carries metadata, and the file backend writes `pr-str`, so a
row whose `data` slot carries a metadata-bearing literal materializes,
loses its metadata, and makes the store unopenable on replay. The codec
deliberately retains non-position metadata in payloads. `dao.jing.md`
names code-as-tuples as the producer that makes this blocking. U10 must
either land the fail-closed check in `dao.jing.file` and the transit codec
or refuse metadata-bearing payloads at the encoder observer; that is a
`dao.jing` unit and is on the Boundary table.

### D4 — the shape of the medium and batch coordinates [owner + `dao.stream.md`]

Item 3 says "the medium identity coordinate is whatever the composition
names its program medium by; this document does not fix its shape." The
sweep narrows what is available:

- **Medium.** `dao.stream.v2` descriptors carry `:dao.stream/identity`, a
  logical-stream identity (`dao/stream/v2.cljc:115,153,302`), already used by
  `test_utils/make-observer-session` to attach. This is the obvious
  candidate and needs only a ruling that it is the coordinate.
- **Batch.** This is the real gap. v2 cursors are opaque and positionless
  by contract; `yin.vm.macro.md`'s `:yin/source-batch` is the expander's
  own `:t` counter (`macro.md:224,676`), local to one `ctx` and reset by
  any rebuild (`:871-872`), which is the very ambiguity §8.4.1's
  incarnation token was minted to escape. A batch coordinate that survives
  a restart needs either a transport-minted, serializable batch identity
  (a `dao.stream.md` extension no consumer has asked for), or a
  composition-minted one (the incarnation token plus the counter, which
  makes every source occurrence expander-relative), or the batch payload's
  own content address (which collapses two admissions of identical
  batches, the case §2.5 says must stay distinct).

No default is offered; the three options trade portability against
`dao.stream.md`'s cursor rules in ways the owner has ruled on before (the
retirement plans' "consumers never construct cursor internals"). Gates
U15/U16 and the provenance half of U4: U4 takes `origin` as an opaque value
and stores it verbatim, so it does not wait.

### D5 — build the expander datom-native first, or row-native [owner]

`yin.vm.macro.md` is specified over datom batches end to end: admission
indexes datoms, harvest iterates the datom vector, stand-ins are eids, the
event schema declares refs (`macro.md:227-357`). Code-as-tuples §8.5
replaces the datom-borne `:macro?` flag with an occurrence-bound
declaration in an ordered batch, and §9.1 asks the datom adapter to derive
the harvest catalogue from entity order. Building the expander as
`macro.md` specifies and then adapting it (the §9.1 datom-batch adapter is
the bridge) is two units; building it row-native from the start is one
unit against a contract that has not been rewritten for rows (macro.md
mentions neither rows nor code-as-tuples). **Default [J]:** datom-native
first, as specified and reviewed through eight rounds, with the §9.1
adapter as the seam; rewriting `macro.md` for rows is a design round the
owner has not asked for. Either way the expander is the largest unbuilt
unit in this plan and is not a blocker for tuple evaluation without
macros, as §8.5 and item 4 both say.

### D6 — item 8 needs a committed UCF before it needs an amendment [owner]

UCF is untracked. Amending §7.6.1's names paragraph (`ucf.md:779-781`) and
fixed point (`:757-758`), and recording code-as-tuples §7.2's supersession
of `:302-303`, is a 20-line edit once the file is in git. The owner must
first decide that r3 is the revision to commit. This plan does not schedule
the commit; it records that U12, U13, U14 cannot begin until it happens.

## Units

### Phase 0 — corrections to the design, before U1

Doc-only, one commit, this plan's own fixable gaps:

1. §10.13: "the test is writable now" → done, cite `v2_test.cljc:266` and
   `84f8eef`; note the corpus is hand-canonical and that U2/U3 extend the
   law to the yang-compiled parity corpus.
2. §7.1 walker row: mark as pending D2; §6.1/§9.1 already say "loader".
3. §2.1's storage Open Question: promote to §10 as item 15 (= D3), with the
   `dao.jing.md` metadata-carry prerequisite named.
4. §10.3: name `:dao.stream/identity` as the candidate medium coordinate
   and the batch coordinate as the open half (= D4).
5. §10.8: add that UCF is uncommitted (= D6).
6. §9.1 frontends row says `:eid` is dropped from the frontends; note it
   cannot be until the expander stops needing it on the datom path (the
   codec already strips it, so the row side is unaffected).

Criteria: no rule of the design changes; every edit is a status note or a
citation.

### Phase 1 — buildable now, no decision pending

Order is by dependency; U1, U2, U3 are mutually independent; U4 needs U2;
U5 needs U4; U6 needs U4 and U5; U7 needs U2.

**U1 — walker `:stream/close` (§3.2).** An `:eval-stream-close-source`
frame shaped as `:eval-stream-cursor-source` (`ast_walker.cljc:319-326`),
raising `{:effect :stream/close :stream ref}` through `engine/handle-effect`.
Criteria: a parity case in `parity_test.cljc` closing a stream on both
evaluators; `Testing yin.vm.v2.parity-test` in the Node output. Size: an
afternoon.

**U2 — the §7.4 validator and the malformed-row corpus.** A public
`yin.vm.v2/validate-rows` (name per implementer) returning nil or the
first defect `{:rule r :path p}` (or `:id` for an unreached row), rules in
§7.4's order, each assuming the earlier held, mirroring `code/well-formed?`.
`:slot-kind` gains `node` (`jing/segment-address?`), `data`/`key`
(`plain-data?`, moved from `linearize` to a shared home so item 2's one
definition is one function), `sym`, `kw`, `str`, `int`. `:root-reachable`
and an explicit `:acyclic` are added; `semantic-bytecode->ast` calls the
validator first and keeps its own address check. A published set of
malformed row sets, one per rule, lives beside the corpus. Criteria: every
rule has a malformed case that names exactly it; `semantic-bytecode-round-trip-law`
extended to `rows → map → rows` over every program in
`parity_test.cljc`'s corpus and over `yang/compile` of the REPL corpus
(the map side of the law holds only for canonical maps, so yang output is
tested in the rows direction and after one projection); the numeric-key
program stays. Size: two to three days including the corpus.

**U3 — the walker's row loader.** `ast-walker/vm-load-rows` = U2's
validator, then `semantic-bytecode->ast`, then the `assoc` of
`vm-load-program` (`ast_walker.cljc:686-698`). The datom loader stays (D2
decides which one the REPL wires). Criteria: §7.2 part 2 for the walker —
for every corpus program, loading rows directly and loading the datom
batch yield `=` `:program`; a full `parity_test` run with the walker on
rows. Size: a day.

**U4 — lowering over rows to the canonical vector.**
`linearize/lower-rows : {:root :rows} origin → {:vector v :provenance
[[pc origin root path] …]}`. `flatten-program`'s recursion (`linearize.cljc:68-159`)
is kept in shape; `lower-node` takes a row id and reads slots by the
grammar table's positions; `ast-children` becomes a lookup of `node`/`nodes`
positions; the emitted tuple per mnemonic is positional and saturated per
UCF §7.3.2 (`[:call argc tail?]`, `[:closure params pc]`, `[:gensym "id"]`,
refs already resolved to pcs, no header). The path of each emitting node is
threaded down the recursion as §2.5 defines it. `origin` is opaque and
stored verbatim (D4 fixes its shape later; nil is legal). `lower` and
`lower-ast` stay for the datom path until U11 retires them. Criteria:
`linearize_test`'s `worked-example-matches-the-design` and
`evaluation-order-matches-the-walker` duplicated against `lower-rows`;
for every corpus program, `(lower-rows (ast->semantic-bytecode ast))`
decodes (U5) to the same image `load-image` produces from `(lower
(ast->datoms ast))`; provenance has one row per pc and every path resolves
in the occurrence relation (U7) once it exists. Size: three to four days;
this is the largest Phase 1 unit and the one with a real chance of an
ordering bug, which the parity suite catches.

**U5 — the §7.5 validator and the positional loader.**
`code/well-formed-vector?` with §7.5's eight rules, defects naming a pc;
`semantic/load-vector` decoding `(nth tuple i)` into the image, minting a
local segment id below the loaded floor (as `ast-loader` does) and keeping
an `address → local-id` alias column per UCF §7.3.4; the vector's address
is `(jing/segment-key v)`. The projection rule of UCF §7.3.4 (`vector →
datoms`, eid 0 for the segment, `(inc pc)` per instruction) is written as
`code/vector->datoms` so that §7.2 parts 2–4 can be asserted: same image
on both paths for every corpus vector, same refusal for every malformed
vector (a correctly hashed `[[:jump 9]]` included), and two segments loaded
through each path giving the same `:code` map and alias column. Criteria:
those four assertions; `store-image`'s live-id conflict fires identically
on both paths. Size: two to three days.

**U6 — segment rows, the syntactic extraction queries, the footprint
table.** `project-segment : vector → [[pc tag & ops] …]` and its
segment-qualified form; the §7.7 queries over `$ast` and `$code` for store
keys, FFI ops, parked ids, and effect-raising tags/mnemonics, verbatim from
the design; the §7.7.1 footprint table as a data value keyed by contract
`"v2"`; and the conformance test that, for every corpus tree, the
requirement set from the tree equals the one from its lowered segment in
every field the syntactic queries fill. Free names are U7's. Criteria:
the equality test; a `$code` query joining a `:store-put` key to a
`:vm/store-put` row by value across the two relations. Size: two days.

**U7 — the occurrence relation and root-scoped rules.**
`occurrences : {:root :rows} → #{[root path node]}` computed by the same
walk as projection (§6.1) — the AST indexer's structural half, not yet
an observer; a rule set that threads `?root` through `p-up`/`occ-anc`/
`occ-bound?` and constrains the `[$occ …]` join to one root (§4.5's named
gap), walking path prefixes so it is generic over every §2.3 tag; the
free-name extraction query of §7.7 as a function. Criteria:
`query_test`'s mixed case passes against the emitted relation instead of
the hand fixture; a two-tree relation with the same literal path in both
classifies each tree's occurrence against its own binder; a fixture using
`:if`, `:vm/resume`, and the `:stream/*` tags. Size: two to three days.

Phase 1 total: roughly three working weeks for one implementer, less in
parallel lanes (U1‖U2‖U3, then U4, then U5‖U7, then U6).

### Phase 2 — after a decision, then mechanical

**U8 — profile and contract publication (D1).** A section in
`yin.vm.code-as-tuples.md` or a sibling `yin.vm.lowering-profile.md`
pinning `"ast-v1"` by the §5.2.1 table; `yin.vm.semantic.md` amended per
D1 (item 14's binding rule, the `zipmap` corrections, the four drifts, a
revision line, and the `"v2"` contract revision history UCF blocker 6
asks for). Docs only; owner approves.

**U9 — derivation records and the ledger (D1, U8, U4, U5).** Record maps
of §8.2, content-addressed; `:derive` written by `lower-rows`'s caller;
§5.2.2's two-step verify with `:yin.k/hash-mismatch`,
`:yin.k/derivation-mismatch`, `:yin.k/profile-mismatch`; the ledger event
entity as tx-data over a `dao.space` transactor; naming rows and
`query/current` as-of (§8.6); the provenance link from a naming datom's
`m` to its event through the metadata-entity path (`transact.cljc:15-29`),
which item 10 says is untested — the test is this unit's. Criteria: the
§8.6 provenance walk returns the tree a segment came from; retract-then-
reassert keeps the reasserted address; a tampered vector reports
`:yin.k/derivation-mismatch`, not a silent re-lower. Size: three to four
days; the transactor and `query/current` exist, so this is composition.

**U10 — content in `dao.jing` (D3, and the `dao.jing.md` metadata fix).**
Rows and vectors materialized under their addresses; `:yin.code/hash`
written; a loader that fetches by root id (rows) or address (vector) and
runs U2/U5. Criteria: `materialize!` → `get` → validate round-trips every
corpus tree and vector on all three hosts; a metadata-bearing literal
either round-trips or is refused before the write. Size: two days once the
backend question is settled; unbounded until it is.

**U11 — the observer row lane (D2).** `program-loaders` switched per D2;
the encoder observer as a `run-on-stream` consumer that projects each
map-AST batch to rows and forwards; `test_utils/queue-ast!` gains a row
twin; `semantic_stream_observer_test` over rows; the REPL's
`eval-ast` path (`repl/v2/core.cljc:444-449`) stops calling `ast->datoms`
for the semantic VM. `engine/executable-program-datom?` and
`append-program-datoms` get either a row counterpart or a deletion, per
§9.2's "whether any live composition reaches the predicate": the sweep
found no `src/` caller of the v2 `append-program-datoms` beyond its own
overload, so deletion is the default. Size: three days; the risk is in
the REPL, whose corpus tests are the proof.

### Phase 3 — architecture still owed elsewhere

These are greenfield against documents marked Proposed. Estimates are
order-of-magnitude.

**U12 — UCF committed and amended (D6, item 8).** The two §7.6.1 edits
and the §7.3.4 supersession note. A day, after the commit.

**U13 — primitive profiles (item 11, UCF §7.11 blocker 7).** The profile
record shape of UCF §7.5.2, `yin.vm.v2/primitives` published with one
each (`yin/def` and `require` `:effectful`), reverse-lookup uniqueness at
`create-vm`. A week; the design is UCF's, the registry format is unwritten.

**U14 — dependency completion (item 5; U7, U12, U13).** The work-item
fixed point of §7.7.3 with the captured-environment context abstraction,
name discharge by store-slice key or profile, `:complete`/`:incomplete`/
`:blocked`. Two to three weeks and a design round of its own: §7.7.3
states the convergence criterion but not the data structure, and the
store slice and parked slice it walks (UCF §7.6.2–7.6.3) exist only as
prose.

**U15 — occurrence identity end to end (D4, item 3).** The `origin`
value U4 stored opaquely becomes the ruled shape; source positions and
frontend metadata side tables from the frontends; the datom-batch adapter
extended to track path and entity (§9.1). One to two weeks after D4.

**U16 — the expander and the batch protocol (D5, items 3 and 4).**
`macro.md` Phase 1 (`expand-batch` and its ten deliverables, `:946`),
then `ctx :incarnation` and the attempt identity (§8.4.1), the §8.4 event
shape, the §8.5 ordered batch with harvest and declaration catalogues and
their admission validation, and the §9.1 adapter. Four to six weeks; the
expander alone was an eight-round design.

## Dependency order and parallelism

```
Phase 0 ─┬─ U1 ─────────────────────────────┐
         ├─ U2 ─┬─ U3 ────────────────────── │ ─┐
         │      └─ U4 ─┬─ U5 ─┬─ U6 ──────── │  │
         │             │      └───────────── │  ├─ (Phase 1 complete)
         │             └─ (U7 needs U2) ──── │  │
         └─ U7 ─────────────────────────────┘  │
                                               │
D1 ── U8 ── U9 (needs U4, U5) ─────────────────┤
D3 + dao.jing metadata fix ── U10 ─────────────┤
D2 ── U11 (needs U3, U4, U5) ──────────────────┘
D6 ── U12 ── U13 ── U14 (needs U7)
D4 ── U15 (needs U4)
D5 ── U16 (needs U15; macro.md Phase 1 first)
```

Phase 1 needs no decision. D1 and D6 are approvals of text the plan can
draft; D2, D3, D4, D5 are choices among stated options.

## Completion criteria

- **Phase 1:** both evaluators load rows (walker by reconstruction,
  semantic by `lower-rows` + `load-vector`); the four parts of §7.2 pass
  over the parity corpus and a published malformed set for rows and
  vectors; the syntactic requirement set is equal from tree and segment;
  free names come from a root-scoped occurrence query over an emitted
  relation. `Testing yin.vm.v2-test`, `yin.vm.v2.linearize-test`,
  `yin.vm.v2.parity-test`, `dao.space.query-test` in the Node output;
  `clj -M:test`, the shadow `:test` build and `clojure -M:cljd test` green
  (the CLJD lane was blocked on an unrelated bench-file bug at `84f8eef`
  and must be confirmed open before U2 lands).
- **Phase 2:** a derivation record verifies a real lowering and detects a
  tampered one; a name resolves as-of; the REPL runs its corpus over the
  row lane on the semantic VM.
- **Phase 3:** each unit's own document names its acceptance test; this
  plan does not invent them.

## Scope, effort, and whether to start now

**Yes, Phase 1 can start today, and it should start with U2, not with the
round-trip test the brief expected.** The codec that the round-trip law
tests is built and the law passes; the smallest self-contained piece of
code that needs no further decision is the validator that completes §7.4,
because every loader (U3, U5) and the lowering (U4) call it first. U1 is a
free afternoon on the side.

**What Phase 1 is not.** It is not the design. Seven units, about three
weeks, deliver the row lane's mechanics with `origin` opaque, no ledger, no
storage, and no wiring change in the REPL. That is deliberately the
subset the design has settled to the line: §2, §3, §5.1, §5.3, §6, §7.1–7.5,
§7.7's syntactic queries, and the §4.5 occurrence rules.

**What waits, and on whom.** Five decisions, all the owner's, gate the
rest, and the plan drafts what it can for each:

| decision | gates | can be drafted by the plan | needs the owner for |
|---|---|---|---|
| D1 profile publication | U8, U9 | yes, from §5.2.1 | approval; the one-segment-per-lambda ruling |
| D2 walker medium | U11 | default stated | choosing among three topologies |
| D3 row storage grain | U10 | default stated | choosing; the `dao.jing` metadata fix |
| D4 medium and batch coordinates | U15, U16, U4's provenance meaning | the medium half | the batch half, which touches `dao.stream.md`'s cursor rules |
| D5 expander datom-native vs row-native | U16 | default stated | choosing |
| D6 UCF committed | U12–U14 | the amendment text | the commit |

**How much is genuinely speculative.** Phase 3 — items 3, 4, 5, 8, 11 — is
roughly two to three months of work against two documents (UCF, macro)
that are themselves unimplemented proposals, and item 5 needs a data-
structure design §7.7.3 does not supply. Nobody should read §10's "design
work with stated mechanisms" as "one sprint away". Phase 2 is a month once
its four decisions land. Phase 1 is three weeks and is the only part where
the estimate is tight.

**Sequencing recommendation, one line:** land U1 and U2 this week, run U3
and U4 as parallel lanes with U5 behind U4, put D1 and D2 in front of the
owner now so U8 and U11 can follow Phase 1 without a gap, and do not open
Phase 3 until UCF is in git.

## Risk and scope boundaries

**Bigger than believed:** the batch coordinate (D4). Every side table and
every expansion event is keyed by it, `yin.vm.macro.md` has no portable
answer, and `dao.stream.md` forbids the obvious one (a position). It is the
one place where this design and the stream contract have not been put in
the same room.

**Smaller than believed:** the codec, the round-trip law, the nil-fill
binding, the observer prerequisite — all landed. The walker's `:stream/close`
arm is one frame.

**A trap in U4:** the image `load-image` builds folds `:call tail? true`
into the `:tailcall` opcode (`semantic.cljc:568-570`) and drops `tail?`
from the tuple. The canonical vector must keep `[:call argc tail?]` (UCF
§7.3.2's example) and let the decoder fold; a lowering that emits the
folded form hashes a different vector and breaks the projection-path
equality of U5.

**A trap in U2:** `strip-reader-positions` and `same-meta?` took five
review rounds to get right for metadata inside metadata; the validator's
`data`/`key` check must call `plain-data?` on the metadata too
(`linearize.cljc:45-58` already does), or a host object can hide in a
literal's metadata.

**A trap in U10:** `dao.jing`'s file backend and the transit codec drop
metadata silently and fail on replay; the codec keeps non-position
metadata in payloads by design. Storing rows before the backend fails
closed is the one way this plan can corrupt a store.

**Ownership of the design text.** Phase 0 adds status notes and one item;
it changes no rule. Every rule change this plan names (items 8, 14, the
`macro.md` `ctx` field) is on the Boundary table under its owning document.

**Out of scope, explicitly:** the pinned canonical byte encoding (item 1,
`dao.jing.md`); De Bruijn or any register-VM lowering (§2.3 defers it);
UCF's custody, leases, safepoints, fencing (§7.7, §7.4, §7.7.5), which
code-as-tuples touches only through the `:publish` record shape; a v2
telemetry or trace vocabulary; anything in the v1 lineage, which is gone.

## Boundary — what is owed elsewhere

| owed | by | recorded where |
|---|---|---|
| the owner's answers to D1–D6 | owner | this plan |
| `yin.vm.semantic.md` amendments (item 14, D1's drift list, the `"v2"` revision history) | U8, owner-approved | D1 |
| UCF in git; UCF §7.6.1 amendments and the §7.3.4 supersession note (item 8) | owner, then U12 | D6 |
| `ctx :incarnation` and token minting at construction (§8.4.1) | `yin.vm.macro.md`, its next revision | item 3; U16 |
| a portable batch coordinate, or a ruling that none is needed | owner + `dao.stream.md` | D4 |
| metadata carried or refused by `dao.jing.file` and the transit codec | `dao.jing.md`'s open item | D3; U10 |
| the primitive-profile registry format | UCF §7.5.2/§7.11 | U13 |
| a data structure for the work-item fixed point | a design round before U14 | item 5 |
| `macro.md` header revision (3 → 8) and its stale "semantic/linearize do not exist yet" (`:1015`) | `yin.vm.macro.md` status note | Phase 0 may add the note |

## Revision history

- **2026-09-17, architect r1.** First draft. Census corrected the brief's
  central premise: the map↔rows codec exists at `v2.cljc:590-826`
  (`84f8eef`, 2026-09-16) with the round-trip law passing, so item 13 is
  done and the true first buildable unit is the §7.4 validator (U2), not
  the round-trip test. Confirmed no expander, no row consumer, no UCF
  implementation, and that UCF is untracked in git. Classified the fourteen
  items: one done (13), one dissolved (12), two inherited or satisfied at
  the codec (1, 2), seven buildable now in whole or in part (2's
  criterion, 6's syntactic half, 5's extraction half, 13's extension, plus
  §3.2 and §7.4/§7.5 which §10 never listed), four gated on an owner
  decision (7, 9, 10, 14), and five owed to UCF, `yin.vm.macro.md`, or an
  unstated coordinate ruling (3, 4, 5, 8, 11). Sixteen units in three
  phases plus Phase 0; six decisions, of which four have stated defaults.
  Found one open question the design does not carry in §10 (D3, storage
  grain) and one topology ambiguity between §7.1 and §6.1/§9.1 (D2).
