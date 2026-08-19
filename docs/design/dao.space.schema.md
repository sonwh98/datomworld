# dao.space.schema — A Schema Interpreter over the Tuple Space

Status: proposed design note. Nothing here is implemented. The
revision dropped index-membership pruning, restated cardinality-one as a
composition over `current`, replaced Datomic tempid/upsert with lookup refs,
and retargeted audit to the raw view; §8 records the rulings. A completion
pass pinned the interface: the Datomic-style bootstrap (schema as seeded
tuples, §2), the `:db.type/*` vocabulary, the transaction vocabulary
including retraction, the borrowed-realization path, wrapper ownership, and
the test contract (§9). A further ruling stored the schema in the space
itself, Datomic's shape: one source, no side value (§2, §4). A third review
round fixed the extraction surface (history view, not `current` — §4),
registered the published opener and dropped `:schema-address` (§5), ruled
the `:db/*` meta-properties interpreter axioms (§2), and pinned card-many
expansion, sequential lookup-ref resolution, and no-dedup (§3). A final
ruling made strictness a mode: the wrapper rejects only under
`{:strict true}` — the medium never rejects tuples (§3, §6). The motivation
is stated in the doc itself: what schema buys an adopter, and why optional
is the only coherent form (§1).

**Related documents:**

- `docs/design/dao.space.md` — the tuple space this interpreter reads; the
  moduli-space framing
- `docs/design/dao.space.query.md` — the source polymorphism the schema view
  plugs into; the *generic relation positional indexes* open item is the hook
  for schema-directed access paths
- `docs/design/dao.space.index.md` — the covered-index realization and the
  structural `covered-indexes` contract this design re-uses
- `docs/design/dao.jing.md` — the storage boundary shared by every
  interpreter point
- `docs/dao.space.stigmergy.md` — cross-stream identity by shared values,
  vocabulary-as-prompt, and the claim-race ruling schema cannot and does not
  replace
- `docs/design/adr/0001-dao-space-as-storage-boundary.md` — the homomorphism
  this design once mis-cited and now cites correctly (§5)
- `docs/design/adr/0002-share-governed-computation-not-data.md` — controlled
  mode, the enforcement boundary for untrusted writers
- `docs/agents/datom-spec.md` — schema entities named by `:db/ident`
- `docs/datomic.md` — the schema model being interpreted


## 1. Premise: one thing changes

dao.space deliberately enforces no attribute schema: values are heterogeneous,
cardinality is observed rather than declared, ref-ness is asserted by the
consumer, and `query` "enforces no schema; any schema policy belongs on the
write side" (`dao.space.query.md`). That is identity, not omission — Datomic's
schema is an expression of a centralized transactor's authority, and dao.space
has no such point: every agent appending to its own stream is its own
transactor.

A schema interpreter therefore does not add schema *to* dao.space. It is a
neighboring interpreter point that changes exactly one thing — the
interpretation function — while everything beneath (streams, segments,
tuples, the query engine, the manifest format, the existing
`current`/`history` views) stays untouched. A raw dao.space agent and a
schema'd agent share one medium and read the same log differently; both can
publish manifests over the same datoms. Schema is a lens, not a cage — the
cage exists only for writers who opt in, and even then its latch is a mode
(§3): the medium never rejects tuples, and strictness is an interpretation a
writer declares.

**What opting in buys.** The log is append-only — there is no delete — so a
wrong tuple appended today is eternal, correctable only by interpretation or
compensation. Schema moves mistakes to the one moment they cost nothing:

- **Errors at the only cheap moment.** Strict mode rejects at emission: the
  typo'd attribute, the wrong-typed value, the dangling ref never land. In a
  mutable store schema is a convenience; in an eternal log it is the only
  cheap correctness gate.
- **Declared shape, not observed shape.** Card-one is a contract, not a
  coincidence of what the log holds: the collapse guarantees exactly one
  value, and the shape of an answer stops depending on how many datoms
  happen to be present.
- **Identity by value.** Entity ids are stream-local integers. Unique
  attributes make identity a *value* (`[:person/email "…"]`) — lookup refs,
  upsert-style addressing, and the sanctioned cross-stream bridge.
- **A queryable vocabulary treaty.** Stigmergy's precondition is a shared
  vocabulary; schema-as-datoms makes it a query a joining agent runs at
  startup — machine-readable, attributed, versioned on the `t` axis —
  instead of folklore in READMEs and hand-written prompts.
- **Definable violations.** "Breaks the schema" is a predicate only if a
  schema is held. Lax mode plus audit is trust-but-verify: nothing
  rejected, conformance measurable per writer through the `m` slot.
- **Honest history for everyone.** The wrapper's supersession emission keeps
  raw readers coherent too; without it, writers hand-roll retraction logic,
  mostly don't, and every reader pays for the incoherence.

Underneath all six: **declared invariants are assumptions readers stop
re-checking** — each constraint validated once at the gate is a check that
thousands of reads do not perform. Schema amortizes validation:
check-once, assume-many.

**Why optional is the point, not a dilution.** A mandatory schema needs an
enforcer with authority over every writer — a central transactor,
precisely the component dao.space deleted. No such point exists, so
optional is the only form schema can take in a decentralized medium: a
treaty among signatories, not a law over subjects. And the medium serves
mixed populations — telemetry, traces, mechanical tuples that never asked
for a vocabulary, beside schema'd facts — where forcing schema on every
tuple would kill the generativity that *is* the tuple space. The
dynamically-open medium stays open; participants opt into as much static
meaning as pays for itself: none, services-only (lax), or teeth
(`{:strict true}`). The value of a shared vocabulary scales with adoption —
a schema used by one agent is private lint; used by a fleet, it is the
coordination contract. That is why it lives in the space rather than beside
it: opting in is also joining.

The interpreter is two clients, one per boundary: the **schema transactor**
(§3) interprets desired-state transactions into validated appends, and the
**schema view** (§4) interprets the log into schema-resolved facts. Both are
ordinary consumers of dao.space — both read the schema tuples through the
public `q` surface, never through a private fold over raw rows.

The Datomic feature mapping, settled up front:

| Datomic schema feature | dao.space.schema's answer |
|---|---|
| `:db/valueType` | Write-time type predicate on `v` (validating wrapper, §3.1) |
| `:db/cardinality` | A **view rule, not a write constraint** (§3.2, §4) |
| `:db.type/ref` | Write-time existence check against the current view *plus entities created earlier in the same record* — beyond Datomic, which has no referential integrity; here restrictions are a feature |
| `:db/unique` | Lookup-ref resolution before append + duplicate rejection (§3.1); no id minting |
| `:db/index` | **Deferred** — published covered sets remain complete (§5); schema-directed membership waits on the generic relation positional indexes open item |
| `:db/isComponent` | **Declined** (§8): cascading retraction has no append-only write form; any revival is a view-rule derivation, never read-side writes |
| `:db/ident` | Schema entities are ordinary datoms stored in the same space as the data, named by `:db/ident` value |
| `alterSchema` | Appends. Schema evolution is datoms at new `t`; semantic time travel via `as-of` on the schema itself (§2) |


## 2. Schema representation: schema-as-datoms, named by value

The datom spec already sanctions the representation: "a schema entity in e
position takes an ordinary stream-local id named across streams by its
`:db/ident`" (`src/cljc/dao/datom.cljc`, `first-user-id`). Schema lives in the
medium it describes:

```clojure
;; t/m elided for readability — these rows are bounded d5, [e a v t m],
;; exactly like any other datom source (see below)
[se :db/ident :person/name]
[se :db/valueType :db.type/string]
[se :db/cardinality :db.cardinality/one]
[re :db/ident :person/friends]
[re :db/valueType :db.type/ref]
[re :db/cardinality :db.cardinality/many]
```

**The schema lives in the space, Datomic's shape.** Schema rows are stored in
the same bounded d5 source as the data — the agent's own stream, or the
shared space — never passed as a side value. They are distinguished by the
fixed `:db/*` attribute vocabulary; installing schema is transacting schema
tuples through the ordinary write path (the bootstrap first, below, then user
attributes). A raw reader sees them as ordinary tuples; the schema reader
finds them by vocabulary.

**The source must be d5, not a flat d3 relation.** Everything this design
does with the schema travels its `t` axis: "what did this attribute mean
last Tuesday?" is an `as-of` query over the same source, and §4's
`:schema-as-of` is only well-defined because the rows carry `t`. A d3
relation cannot be `as-of`'d.

The schema is also **self-hosting**: it is read through the same machinery it
describes. Schema attributes are themselves card-one — re-asserting
`:person/name`'s cardinality at a new `t` supersedes the earlier declaration,
resolved by the same §4 rule that resolves data. One interpretation function,
applied twice.

**The bootstrap is seeded tuples, Datomic's move.** Datomic creates every
database with its core schema entities already installed — `:db/ident`,
`:db/valueType`, `:db/cardinality` are themselves entities, queryable like
anything else. dao.space.schema ships the same shape: a fixed **bootstrap
relation**, a small vector of d5 tuples declaring the `:db/*` vocabulary's
own properties (every `:db/*` attribute is card-one; `:db/ident` is unique;
`:db/unique` and `:db/index` are card-one booleans/keywords). The bootstrap
is data — the stream's genesis transaction, publishable and inspectable like
any other tuples — never a code-level constant. The interpreter's code
understands the fixed `:db/*` *names* the way `q` understands its query
vocabulary; everything above those names is tuples.

**The `:db/*` meta-properties are interpreter axioms — this grounds
self-hosting without regress.** The seeded tuples are provenance —
queryable, publishable, honest history — but not authority: any writer can
append rows re-declaring or retracting them, so the stream cannot be what
pins them. Rows describing the `:db/*` attributes themselves resolve from a
fixed axiom set in code (the way `q` understands its query vocabulary);
re-declaring or retracting them in a user transaction is rejected at the
wrapper, and the view's fold resolves meta-properties from the axioms
regardless of what the stream says. User attribute declarations
(`:person/name` and friends) are data and evolve by append. The vocabulary
is frozen: the five property names enumerate the extraction patterns; an
unknown `:db/*` row is rejected at the wrapper and ignored by the view.
There is no schema-of-schema-of-schema to need.

Consequences:

- **Evolution is appends.** `:person/name` becoming card-many is a new datom
  at a new `t`. Semantic time travel falls out of schema-as-data having a
  `t` axis.
- **Cross-stream identity is by shared value.** Entity ids are stream-local,
  so a schema entity in stream A and the "same" one in stream B are joined by
  their `:db/ident` value — the stigmergy cross-stream identity ruling, with
  no sixth tuple slot and no reserved entity block.
- **No side channel.** The schema is not a second db-value carried next to
  the data; it is read from the same source, and therefore discoverable by
  `q` — the stigmergy "vocabulary is the prompt" convention becomes a
  literal query an agent's runtime runs at startup.


## 3. The write boundary: a validating wrapper

Datomic's schema is enforced by its transactor. Here the schema interpreter
is a library duty wrapping `dao.space.transactor`'s `:transactor` stream
wrapper — the same layering `yin.vm` already demonstrates with its own
Datomic-style schema map and `datoms->tx-data`
(`src/cljc/yin/vm.cljc`, `schema`): a client above the space declaring and
enforcing a vocabulary at its own write boundary.

The wrapper validates **before emission** — the only sane order in an
append-only world, because there is no rollback after an append. A rejected
transaction throws with nothing appended; the stream stays clean.

**The transaction vocabulary** follows Datomic's shapes:

- `{:db/id id-or-lookup-ref, attr val, …}` — entity map: desired state. For
  a card-one attribute with a different live value, the wrapper emits §3.3
  supersession; card-many attributes merge.
- `[:db/add e a v]` — bare assert. On a card-one attribute with a different
  live value it is rejected: be explicit (retract first, or use the map
  form).
- `[:db/retract e a]` / `[:db/retract e a v]` — explicit retraction: the
  attribute (or that value) becomes absent.
- Lookup refs are accepted wherever an entity id is expected — `:db/id`
  position and `:db.type/ref` values alike — and resolve before emission
  (§3.1). Resolution is sequential per item within a record: a `:db/id`
  lookup ref may target an entity created earlier in the same record,
  matching ref-value resolution.
- **Card-many collection expansion.** A collection value on a card-many
  attribute (`{:person/friends #{8 9}}`) expands to one datom per element —
  the `yin.vm` precedent (`datoms->tx-data`).
- **No dedup.** The wrapper does not detect no-ops: retracting an absent
  fact, or re-asserting the live value, appends as told. The log is history;
  interpretation decides visibility.

An attribute named by the schema but with no `:db/cardinality` declaration is
card-many: schema rows are additive constraints, undeclared means
pass-through.

**Strictness is a mode, not a property of the space.** Generativity is the
tuple space's identity — tuples can be appended with no predefined schema —
so the wrapper rejects only under an explicit strict interpretation:
`(schema/transactor local-stream intake-pool {:strict true})`. The default
is lax: the wrapper provides its services (lookup-ref resolution, §3.3
supersession emission, collection expansion, the current-state index) and
appends transactions that violate declared constraints — valueType
mismatches, dangling refs, unique duplicates, self-contradictory card-one
records, malformed schema rows — leaving them to the §6 audit. Strict mode
rejects them before emission, as §3.1 details. One rejection is
mode-independent: an unresolvable lookup ref, because there is no value to
write and minting is forbidden. The reader is always lax — §4 never rejects.
Strictness is a writer's stance, never the medium's.

### 3.1 What each declaration checks

Each check below rejects in **strict mode**; in lax mode the transaction
appends and the violation becomes an audit finding (§6).

- `:db/valueType` — type predicate over `v`, from the fixed `:db.type/*`
  vocabulary: `string`, `keyword`, `boolean`, `long`, `double`, and `ref`
  are supported on every host (JVM, ClojureScript, ClojureDart); `uuid`,
  `instant`, and `bytes` are deferred until their representations are pinned
  cross-platform. An unknown `:db.type` in a schema is rejected at
  construction.
- `:db.type/ref` — target `e` exists in the current view **plus entities
  created earlier in the same atomic record** (a batch may create a parent
  and its referrer together; a check against only the prior view would reject
  every such batch). Lookup-ref values in ref position resolve through the
  same unique index before the check.
- `:db/unique` — duplicates rejected; **lookup refs** `[:unique-attr value]`
  supplied in `:db/id` position are resolved against the wrapper's index
  **before emission**. An unmatched lookup ref is a rejection, never a
  minting — the transactor's own ruling forbids it: ":db/id is required: a
  durable log cannot mint per-batch tempids without colliding across
  appends" (`src/cljc/dao/space/transactor.cljc`, `entity->datoms`). New
  entities carry an explicit `:db/id` from the caller. A batch is validated
  against the index *and against itself*: two entities in one record
  asserting the same unique value is a self-conflict.
- **Schema rows themselves** — the wrapper validates `:db/*` rows at
  transact: known property names only, legal `:db.type/*` and
  `:db.cardinality/*` values, keyword `:db/ident`s, `:db/ident` uniqueness
  on the vocabulary itself, and no re-declaration or retraction of a `:db/*`
  attribute's own properties (§2 axioms).

**Wrapper state, declared.** The wrapper maintains one per-wrapper
current-state index — built in the same cursor-zero full scan that already
derives `next-t` (no extra IO), incremented per successful append — serving
three duties at once: uniqueness, current values for supersession (§3.3), and
lookup-ref resolution. It is per-wrapper and single-writer, the same duty
class as the `t` watermark, not new hidden mutability. **The schema is read
from the stream at open, with `q`**: the wrapper drains its retained history
into a relation and runs the same extraction query as the §4 view — one
extraction function, two interpreters. The stream carries its own schema
(§2). Adopting evolved schema datoms means constructing a new wrapper
(cheap — the open scan is already full-history). There is no live schema
mutation inside a running wrapper. Ownership is explicit: `schema/transactor`
opens the inner `:transactor` and owns it — its `close!` delegates inward,
and the inner wrapper never leaks.

### 3.2 The crucial non-check: cardinality

**Cardinality-one is not a write constraint.** In an append-only world,
card-one is temporal supersession — a view rule, not a rejection. Two rows
`[e a v1 t1]` and `[e a v2 t2]` with different values are both legal facts;
the interpretation decides which is visible. This is precisely Datomic's own
semantics (its log is append-only; card-one is resolved as of a db value),
made explicit by putting it where this architecture puts all semantics: in
the view interpreter, §4.

A transaction that would *assert* two values for a card-one attribute within
one atomic record is rejected at the wrapper in strict mode — not because
the log cannot hold it (in lax mode it appends, and the §4 tie resolves it),
but because no interpretation of a single transaction's self-contradiction
is useful. This is a **wrapper-side, strict-mode** ruling; the
**reader-side** rule for a stranger's legal log carrying same-`t` asserts is
the tie-break in §4, which never rejects.

### 3.3 The mandated update shape: explicit supersession

When the wrapper translates an update of a card-one attribute whose current
value is `v_old`, it emits **retract-old + assert-new in one atomic record at
the same `t`**: `[e a v_old t retract]` and `[e a v_new t assert]`. Three
reasons, in decreasing weight:

1. **Explicit causality** is a project invariant, and the transactor already
   preserves explicit `m` ops.
2. **View coherence.** Under the *raw* `current` view the two rows are
   different `[e a v]` keys, so resolution yields exactly `{v_new}`: a raw
   reader and a schema'd reader agree on one value. Assert-only writes would
   leave two live values visible to every raw reader, quietly contradicting
   §1's promise that both interpreters "read the same log differently"
   without divergence.
3. **Role correctness.** The §4 collapse becomes a safety net for foreign
   writers who do not supersede explicitly — its correct role under §6's
   table — rather than the primary mechanism for the wrapper's own writes.

Cost: the wrapper must know the current value of each card-one attribute it
updates. The per-wrapper current-state index (§3.1) already holds it — no
new duty.


## 4. The read boundary: one new view type

Card-one resolution is a **composition of interpretations**, not a rival
grouping of raw d5. First, the schema is fetched with `q` over the source's
**history** view — not `current`, which projects to d3 and would leave the
fold no `t`/`m` to supersede with. The bootstrap's fixed names enumerate the
patterns, so one disjunctive query (`or-join`, `ground`-ing each name)
returns every attribute entity and its full d5 property rows. The
interpreter's one private fold then applies retraction and collapses the
surviving property values per property (greatest `(t, m)`, tie → least `v`)
— rules `q`'s surface cannot express. Then the data, in three steps:

1. Interpret via `dao.space.current` — group by `[e a v]`, greatest `(t, m)`,
   retractions removed, conflicting same-`[e a v t]`-different-`m` history
   rejected, exactly as today (`current-state-seq`).
2. Collapse the surviving asserts by `[e a]` **for the attributes the schema
   names card-one**: greatest `(t, m)` wins; on a full `(t, m)` tie, the
   least `v` under the canonical value comparator (`index/compare-vals`)
   wins — arbitrary but deterministic from the data alone, independent of
   arrival order, and never a rejection. (A stranger's legal log may carry
   same-`t` asserts; a reader must read it.)
3. **Unschematized attributes pass through unchanged** — card-many
   semantics, the raw `current` view. The schema collapses only what it
   names card-one; "a lens, not a cage" holds at attribute grain.

The out-of-order retraction that breaks a naive `[e a]` re-grouping
(`[e a v1 1 assert]`, `[e a v2 2 assert]`, `[e a v1 3 retract]`) resolves
correctly here: step 1 removes `v1` (its own key's latest row is the
retraction), leaving `v2` live; step 2 collapses nothing further. The
same-`t` assert+retract update resolves correctly because the retraction
cancels `v_old` in step 1, before any collapse.

The descriptor is a pure semantic view value:

```clojure
{:dao.stream/type :dao.space.schema/current
 :source <bounded d5 descriptor>          ; data and schema rows together
 :dao.stream/bound <inherited from :source>}   ; schema constrains
                                               ; interpretation, not extent
```

The view finds the schema in the same source: rows whose attribute is one of
the fixed `:db/*` names (§2), resolved through the bootstrap. `:as-of` applies
to data and schema alike by default; `:schema-as-of` optionally resolves the
schema at a different point of the same `t` axis. Neither key is part of the
bound, which inherits from `:source` exactly as `current`'s descriptor does.

**Realization — the first view through the `defopen` seam.** `current` and
`history` are *not* realized through `defopen`; they are special-cased in
`realize-db-value!` / `realize-datom-view!`. The schema view instead
registers `(ds/defopen :dao.space.schema/current …)`, whose body opens
`:source` once, interprets (the extraction and steps 1–3 above), **drains
and closes the inner stream**, and returns a self-contained closed
`ViewStream`
advertising `:fact? true`. Because a closed realization satisfying
Reader+Bound flows through `realize-db-value!`'s existing borrowed path —
`fact-view-realization?` builds the fact-index from its rows — **`query.cljc`
needs zero changes**. The ownership clause is load-bearing:
`ViewStream`'s `close!` closes nothing, and the query layer's `::owned`
tracks only the returned realization — so the `defopen` body itself must
close the inner source or it leaks.

Like `current`, the view is dual-path: given a descriptor it returns the view
value above; given an already-opened closed realization it validates it as
borrowed (`validate-borrowed!`), interprets through the same steps, and
returns the derived `ViewStream` directly. Both paths share one
interpretation function; source polymorphism is preserved.

One pull caveat, stated narrowly: card-one attributes now read unambiguously
as scalars, but a card-many attribute holding a single value still presents
as a scalar under the count convention. Schema-aware pull, or a cardinality
marker on the view, is a follow-up — noted in §8, not designed here.


## 5. Access paths: complete covered sets, deferred schema-directed sets

The publisher emits **complete four-tree covered sets** via the standard
`publish-index!` move. Schema-directed membership (a pruned AVET, a
unique-attribute structure) is **deferred**, for two verified reasons and one
corrected citation:

- `select-by-index` routes unconditionally by binding shape — `a`+`v` bound
  goes to AVET, `v` bound to VAET — with **no completeness fallback**; a
  pruned tree answers `∅` where the log has matches. The public `datoms`
  selector post-filters candidates but cannot recover non-candidates.
- The manifest stores a single scalar `:count`, and `restored-indexes`
  threads it to all four trees ("all four restored trees report the same
  count they actually contain") — pruned membership restores with false
  cardinality.
- The ADR 0001 homomorphism licenses *partitioned indexing over the same
  logical set* — it says nothing about changing the indexed set, and cannot
  justify pruning.

Schema-directed membership becomes safe exactly when the *generic relation
positional indexes* open item in `dao.space.query.md` lands: its planner rule
("selects only from those supplied indexes and falls back to a relation scan
when none fits") is the precondition that makes partial membership a cost
decision rather than an answer change.

There is no `:schema-address`: the schema lives in the source (§2), so
there is no standalone schema segment to content-address, and the
provenance of an interpretation is the source itself, optionally `as-of`'d.
The manifest is schema-independent — a complete covered set over the d5 log.

The published descriptor is its own type, realized by a registered
`(ds/defopen :dao.space.schema/published …)` that delegates to the
`published-index` realization — it cannot reuse that opener directly, since
`open!` dispatches on `:dao.stream/type` and the index opener demands exact
descriptor equality. The shape carries what `validate-descriptor!` requires:

```clojure
{:dao.stream/type :dao.space.schema/published
 :dao.stream/bound {:manifest-address :segment/sha256-…}  ; the exact bound
 :dao.stream/comparator :dao.space.index/eavt
 :content-store <coordinate>
 :manifest-address :segment/sha256-…}
```


## 6. Enforcement scope: who is bound

| Writer | Enforcement |
|---|---|
| The agent itself | The validating wrapper, opt-in, rejecting only in strict mode — every agent is its own transactor, so schema binds exactly those who adopt it, as tightly as they declare |
| Lax writers and strangers on shared storage | No rejection — but **audit-as-query** (below) |
| Untrusted guests | Controlled mode (ADR 0002): a capability-tokened gatekeeper confines accepted writes to schema-valid ones. Enforcement moves to the capability boundary, not the medium |

**Audit-as-query runs against the raw view.** The audit target must be plain
`dao.space.current` — group `[e a v]`, pre-collapse — because the schema'd
view has already collapsed card-one violations and cannot exhibit them. The
two violation classes are different query shapes: type/ref violations are a
per-datom join of the raw view against schema datoms; card-one violations
are a grouping query over the raw view (one `[e a]`, two live `v`s). The
default builtins registry has no type predicates — auditors ship their own
via `{:fns …}`, which `q` merges over builtins; builtins stay closed in this
design.

The one thing schema cannot buy is **multi-writer uniqueness**. Two agents'
streams share no clock and no authority, so a unique attribute *across
streams* is still the claim race, resolved by the documented read-side rules
(leases, tie-breaks — `dao.space.stigmergy.md`). Schema constrains streams,
not fleets; it removes intra-stream anarchy and leaves inter-stream
coordination exactly where it was.


## 7. What it does not touch

`dao.stream`, `dao.jing`, the datom spec, the manifest format, the Datalog
engine, `current`/`history`, `query.cljc` itself, and every existing caller.
The interpreter is four moving parts — schema representation conventions
(§2), the validating wrapper (§3), the read view (§4), the publisher (§5).
The dependency direction is one-way: `dao.space.schema` requires
`dao.space.query` and `dao.space.transactor`; neither ever requires it. The
schema interpreter is two clients of dao.space, not a fork of it. Both
openers register at namespace load, so opening a schema descriptor requires
`dao.space.schema` to be `require`d first — it fails closed otherwise:

```clojure
(require '[dao.space.query :as query]
         '[dao.space.schema :as schema])

;; the wrapper — no schema argument; the stream carries its own schema.
;; Lax by default; {:strict true} rejects violations before emission
(def log (schema/transactor local-stream intake-pool))

;; install: schema tuples are transacted through the ordinary write path,
;; bootstrap rows first, then user attributes — the same vocabulary as data
(schema/transact! log (schema/bootstrap))
(schema/transact! log [{:db/id 16 :db/ident :person/name
                        :db/valueType :db.type/string
                        :db/cardinality :db.cardinality/one}])

;; read — one source (any bounded descriptor of the stream's datoms: a
;; published manifest, or a drained relation in tests); the view finds the
;; schema rows by vocabulary
(query/q '[:find ?n :where [?e :person/name ?n]]
         (schema/current data-source))             ; bounded d3 db-value

;; write — same stream, validated against the schema it carries
(schema/transact! log [{:db/id 17 :person/name "…"}
                      {:db/id 18 :person/friend [:person/email "…@…"]}])
;; one atomic record; the lookup ref resolves before emission and may
;; target an entity created earlier in the same record

;; publish — complete covered sets over the same d5 log
(schema/publish! log)
;; => {:manifest-address …}
```


## 8. Rulings and open items

- **Schema-directed covered sets: deferred, not dropped.** Pruning is
  unsound today (no completeness fallback in `select-by-index`; single
  manifest `:count`); the supplied-index planner open item is the
  precondition that makes partial membership safe.
- **`:db/isComponent`: declined.** Cascading retraction has no append-only
  write form, and the view-rule alternative — a recursive visibility fold
  over component closures — depends on stream-local ref resolution and buys
  little where nothing is deleted. Any future revival is a view-rule
  derivation only, never read-side writes.
- **Tie rulings, two sides.** Wrapper side: self-contradictory single
  records are rejected (§3.2). Reader side: a stranger's same-`t` card-one
  asserts resolve by the §4 tie (greatest `(t, m)`, then least `v` under
  `compare-vals`) — arbitrary-but-deterministic, never a rejection. Pin both
  by test when implemented.
- **Schema pinning.** Wrappers and views name their source — a bounded d5
  descriptor, optionally `as-of`'d — and read the schema from it. Evolution
  never rewrites history; each `as-of` sees the schema it names. A manifest
  does not encode its schema; there is no `:schema-address` (§5).
- **Cross-stream refs are interpreter-relative.** An `e` in one stream names
  nothing in another. The sanctioned bridge is unique attributes whose
  *values* are globally meaningful (`:task/id "uuid-…"`) plus explicit query
  unification — the existing ruling; a schema'd database spanning streams is
  a fleet convention, not a mechanism.
- **The current-state index is wholesale today.** Built by the wrapper's
  full-history open scan; incremental maintenance rides the same open item
  as incremental indexing in `dao.space.index.md`.
- **Pull's card-many-single-value ambiguity remains.** Card-one now reads
  unambiguously as a scalar; a card-many attribute holding one value still
  presents as a scalar under the count convention. A cardinality marker on
  the view, or a schema-aware pull, is a follow-up — noted, not designed
  here.


## 9. The executable contract (when implemented)

`test/dao/space/schema_test.cljc`, written first. The matrix the rulings
prescribe:

- card-one composition: the out-of-order retraction trace (§4) resolves to
  `v2`; the same-`t` assert+retract update resolves to `v_new`.
- the reader tie: a full `(t, m)` tie collapses to the least `v` under
  `compare-vals`; never throws.
- unschematized attributes pass through; undeclared cardinality is
  card-many; an unknown `:db.type` is rejected at construction.
- wrapper (strict mode): valueType rejection, ref existence including
  same-batch targets, batch self-conflict on unique values, lookup-ref
  resolution, map-form supersession emitting retract+assert at one `t`,
  `[:db/add]` collision on a card-one attribute rejected.
- modes: lax appends each of those violations and the audit query finds
  them; the same transactions reject under `{:strict true}`; an unmatched
  lookup ref rejects in both modes.
- bootstrap: the seed relation reads back as the `:db/*` vocabulary's own
  properties; re-declaring or retracting a `:db/*` property is rejected at
  the wrapper and ignored by the view (axioms, §2); duplicate `:db/ident`
  declarations are rejected.
- schema-in-source: install-by-transact then read; the view and the wrapper
  find schema rows in the same source; `q` over a raw view sees them as
  ordinary tuples.
- extraction: both interpreters fetch the schema through the public `q`
  surface — the same query, pinned once; property collapse supersedes
  re-declarations.
- view: descriptor bound inherited from `:source`; the borrowed-realization
  path answers identically to the descriptor path; no inner-stream leak
  after `close!` (the ownership trap).
- extraction surface: the schema query runs over the history view and binds
  `?t ?m` — a `current`-view extraction cannot supersede; the fold applies
  retraction, then greatest `(t, m)`.
- as-of: `:as-of` bounds data and schema; `:schema-as-of` re-reads the
  schema at a different point of the same `t` axis.
- audit: type-predicate auditors ship `:fns`; card-one violations surface
  only on the raw view.
- wrapper no-dedup: retract of an absent fact and re-assert of the live
  value append as told; card-many collections expand one datom per element.
- publisher parity: the published descriptor opens through its registered
  opener and answers as a `q` db-input; `q` answers identically before and
  after publish.
