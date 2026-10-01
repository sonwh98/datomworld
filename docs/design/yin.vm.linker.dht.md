# yin.vm.linker over dao.jing.dht: publish, load and evaluate code by name

Status: **contract frozen 2026-10-01 (linker-over-DHT epic slice L-design,
revision r5); nothing is implemented.** Revision r5 closes the one finding
of the r4 sign-off
(`collab/1790808100000-architect-linker-over-dht-design-doc-signoff-r4.gpt-6-sol.findings.md`):
`:republished` reports any result change after the first report, whatever
wrote the ledger (5.5.3). Revision r4 closed the two
findings of the r3 sign-off
(`collab/1790808100000-architect-linker-over-dht-design-doc-signoff-r3.gpt-6-sol.findings.md`):
the repair queue has its own capacity and its own admission turn, and
publications awaiting a first report are bounded (5.5.2, 5.5.4). Revision
r3 closed the two
lifecycle findings of the r2 sign-off
(`collab/1790808100000-architect-linker-over-dht-design-doc-signoff-r2.gpt-6-sol.findings.md`)
and adopts owner decision 5, automatic retry while open: section 5.5 is
rewritten around one complete publication ledger. Revision r2 closed the five findings
of the withheld sign-off
(`collab/1790808100000-architect-linker-over-dht-design-doc-signoff.gpt-6-sol.findings.md`):
the bounded replicate backlog and the publication result (5.5), dependency
names (7.4), the `:unaskable` and walk-defect shapes (4.3, 9), and the
footprint computation (5.2). Section 2 records the starting tree. Section 12
is the slice plan, L0 to L5. Subordinate to
[`datom.world.md`](./datom.world.md),
[`yin.vm.linker.md`](./yin.vm.linker.md),
[`dao.jing.dht.md`](./dao.jing.dht.md),
[`yin.repl.link-policy.md`](./yin.repl.link-policy.md) and
[`yin.repl.dao.space-index.md`](./yin.repl.dao.space-index.md). In sections
3 to 11 every sentence is a rule.

Inputs: the lead design
(`collab/1790806000000-architect-linker-over-dht.claude-fable-5-1.findings.md`),
the second opinion
(`collab/1790806000000-architect-linker-over-dht-second-opinion.gpt-6-sol.findings.md`,
AGREE-WITH-CHANGES, corrections adopted), and the owner decisions of
2026-10-01
(`collab/1790808000000-orchestrator-linker-over-dht-owner-decisions.md`).

## 1. Overview and scope

DHT epic S5 gave any Clojure program, and `yin.repl` through host functions,
one path to join the DHT, load a published covered index from its manifest
address, and query it. Loading code *for evaluation* is a different path: the
linker resolves a module name to a module manifest address through a name
environment, and links the manifest's image over a content pair. This
document joins the two.

It answers one question: how does a second process, attached to the same
DHT, load a module by name and evaluate it, with every trusted claim stated?

Owner invariants this contract is bound by:

- "plain clojure code should be able to query for code in the dht. the
  yin.repl should use the same path as the clojure repl via host-functions"
  (2026-10-01). Every operation here is a plain Clojure function;
  `yin.repl` adapts effects to those functions and holds no second loader.
- Code is shared over the stream linker; the linker boundary is
  `dao.stream`; local versus remote linking is the composition's choice of
  streams.
- `dao.stream` is peer to peer: no server, no client, no privileged node.
  Publishing and trusting are per-node composition, never network roles.
- Derive, don't persist: nothing is stored that a query over existing rows
  derives.
- `dao.jing` is syntax and agents are semantics: the store stays passive and
  payload-agnostic.
- Code and continuations are datoms.

**Out of scope:** latest-root discovery, key rotation and revocation, the
persistent stepped linker, the `:lease` link policy, authenticated node ids.
Section 13 lists every deferral.

## 2. The starting tree (master `c66809fa`)

| Fact | Where |
|---|---|
| A link attempt builds a fresh link state and fresh cursors, runs `link-manifest` synchronously for at most 64 drive rounds, and reports `:pending`. The `:remote` drive advances only a counter. | `src/cljc/yin/repl/link.cljc:167-227` |
| A `dao.space.dht` node's byte-store handle reads `:local` only. `load-index` stages a miss around the handle: walk against a probing handle, fetch the missing blob with `:jing/get`, walk again. | `src/cljc/dao/space/dht.cljc:457-597`; `dao.jing.dht.md` 5.1 |
| The REPL's index holds AST datoms with `:yin/address` facts naming row addresses. The rows themselves are never stored: `yin.vm/materialize-tree!` has no caller in `src/`. | `src/cljc/yin/repl/index.cljc`; `src/cljc/yin/vm.cljc:1472` |
| A REPL definition is the application `(yin/def 'f value)`: operator the variable `yin/def`, first operand a literal symbol. Definitions are found by query; no definition-name fact exists. | `yin/vm.cljc:296`; `test/dao/space/query_test.cljc` |
| `yin.vm.linker/publish!` stores one format image. Module manifests are assembled only by test helpers. | `linker.cljc:1992`; `test/yin/repl/require_test.cljc:134` |
| The authority fold (`yin.vm.linker.md` 8.2) is implemented and wired into nothing. The REPL's name environment is a plain map. | `src/cljc/yin/vm/linker/authority.cljc`; `yin/repl.cljc:862` |
| The fold resolves a name when every accepted assertion names the same address, keeping all asserters; it refuses only on more than one distinct address. | `authority.cljc:226-255` |
| `yin.repl/recheck-pending` exists; no host driver calls it. A parked require progresses only on a typed line. | `yin/repl.cljc:1848`; `yin/repl/main.cljc:286` |
| The `dao.space.dht` host module answers `load-index`, `load-status` and `q` as effects on the query call pair, threading the node through `query/serve`. | `src/cljc/yin/repl/query.cljc:127-153, 570-616` |
| A module whose export reads another module-level definition from inside a lambda body links on the semantic, stack and register backends. The tree scanner alone retains the read (its path order places the operator's body before the operand that defines the name), and the manifest cannot declare it. | `require_test.cljc:90-98`; `linker_test.cljc` `a-definition-dominating-every-application-discharges-a-body-occurrence`; `yin.vm.linker.md` 4.2 step 5a |
| `dao.jing/canonical-bytes` is deterministic CBOR, identical across hosts. | `src/cljc/dao/jing.cljc:279` |
| A DHT node holds at most 64 pending writes; a write beyond that is `/busy`. | `dao.jing.dht.md` 9 |
| The node's handle appends one replicate request per put, at once, and a publication with any refused blob is reported `:acknowledged? false` with one reason. | `src/cljc/dao/space/dht.cljc:223-227, 421-429` |
| `yin.vm/ast-requirements` answers a tree's store keys (definitions included) and its effects, normalized by `footprint-table`. `yin.vm/profile-of` answers a primitive's profile with its `:yin.k/effects`. | `src/cljc/yin/vm.cljc:415, 1682, 1818` |

## 3. Rulings

| # | Ruling | Source |
|---|---|---|
| R1 | The linker's content source is the `dao.space.dht` node's own store handle, the `:kind :local` row of `yin.vm.linker.md` 6.1. A module's closure is loaded onto the node first, by an explicit closure walker (section 4). The node stays the only step owner; no linker drive steps it. | lead; second opinion |
| R2 | Two things are published: the code index (facts) and the module closure (content). The name binding is one signed envelope in the index. No datom family is added for images. | lead; second opinion |
| R3 | Every evaluated program's rows are materialized into the index store every round. An index `:yin/address` fact names retrievable content. | owner decision 1 |
| R4 | A name is resolved from signed envelopes in explicitly loaded index snapshots, under the fold of `yin.vm.linker.md` 8.2. No name-environment root is published. Direct manifest addresses stay admissible without any signature. | lead; second opinion |
| R5 | The proof kind for a DHT-discovered name is a signature. An attested-log proof does not survive this transport. | lead; second opinion |
| R6 | Signatures are Ed25519 on the JVM, Node and Dart, with cross-host vectors. Canonical signed bytes, key encoding and the principal id are fixed in section 6 before any adapter is written. | owner decision 3; second opinion |
| R7 | A publisher's key is stable and loaded from a file. Loss and replacement are defined in section 6.5. Tests may use ephemeral keys. | owner decision 2 |
| R8 | The same name bound to the same address by several declared principals resolves, with every asserter in provenance. Different addresses refuse `:ambiguous-name`. | owner decision 4 |
| R9 | A module is published explicitly, by name and export list, with its tree derived from indexed code. | adopted recommendation |
| R10 | A `require` under a DHT link source may start a bounded load. The banner and the help say so. Pending and failure are data. | adopted recommendation |
| R11 | The host ticker re-checks a pending run only when a load that run waits on completes or fails. | second opinion |
| R12 | A DHT request budget is not a lease (section 8.3). | second opinion |
| R13 | A publication that is not acknowledged is retried automatically, in the background, while the node is open and within the backlog bound. Retry updates the original publication's ledger (section 5.5). | owner decision 5 |

## 4. The content source

### 4.1 Composition

A DHT link source is `{:kind :dht}` in `yin.repl.link/composition`, chosen by
`yin.repl/create-state` when the index store is `dht:<dir>`. It holds no
handle of its own: every serve round receives the shell's node and reads
content through `(dao.space.dht/store node)`, which reads `:local` only.
`:content-store` and `:content-client` remain, for the other rows of
`yin.vm.linker.md` 6.1, and are refused together with a DHT store.

One link attempt against a DHT source proceeds in this order:

1. Resolve the name to a module manifest address (section 7.3). No
   resolution: refuse with the fold's outcome.
2. Read the module load record for that address on the node (4.3).
   - None: start the load, answer pending.
   - `:loading`: answer pending.
   - `:failed`: refuse (section 9), then `forget` the record, so a later
     require starts a new load.
   - `:loaded`: continue.
3. Check the dependency bindings of the loaded closure (section 7.4). A
   binding that does not hold refuses, before any link.
4. Link with `link-manifest` over a local runtime on the node's store,
   `:derivation :verifying`, `:defer-discharge true`.

A link that follows a `:loaded` record issues no content request to any
peer. A link refusal `:absent` after `:loaded` is a walker defect and is
reported as one (`:yin.link.dht/closure-incomplete`), never retried silently.

`yin.vm.linker/local-runtime` is the one function that turns a `dao.jing`
handle into a link runtime `{:state :drive}` over an in-process ring pair
served by `dao.jing.content/serve-step`. `yin.repl.link` and both test
helpers call it.

**Why not a content client on the node's rings.** The node's request and
answer rings are the `:jing/*` convention, so the shape admits it. The
attempt lifecycle does not: an answer the DHT produces on a later tick lands
for a link state that no longer exists. That composition needs
`link-manifest` as a persistent stepped state machine and is deferred
(section 13).

### 4.2 The module closure walker

`yin.vm.linker.closure/walk` is a pure function over a `dao.jing` handle. It
reads with `dao.jing/get` and a sentinel, performs no stream operation, and
never throws for absent or invalid content.

```clojure
(walk handle manifest-address)
(walk handle manifest-address {:bounds {:max-parts n :max-depth d :max-bytes b}})

;; exactly one of:
{:yin.link.closure/outcome :missing
 :address a :role role :path [...]}
{:yin.link.closure/outcome :complete
 :manifest manifest-address :blobs n :modules [manifest-address ...]
 :requires [{:module manifest-address :name 'other.lib :manifest pinned} ...]}
{:yin.link.closure/outcome :invalid
 :address a :role role :path [...]
 :defect {:code code :detail {...}}}
```

`:requires` lists every `:yin.module/requires` entry of every module in the
closure, in walk order: the requiring module, the dependency's name, and the
address the publisher pinned.

`:code` is one of a closed set. `:detail` is the underlying validator's
defect map when there is one; no consumer compares it.

| `:code` | Raised by |
|---|---|
| `:address-mismatch` | A blob that fails `segment-matches?`. |
| `:manifest-defect` | `manifest-defect`. |
| `:row-defect` | `row-local-defect`. |
| `:record-defect` | `record-defect`. |
| `:derivation-mismatch` | A record whose `:yin.ledger/input` is not the tree. |
| `:index-entry-missing` | An H or R with no `:yin.module/index` entry. |
| `:identity-mismatch` | An image that fails its format's `:identity-matches-fn`. |
| `:parts-limit` | A walk bound exceeded. |

`role` is one of `:manifest`, `:row`, `:record`, `:image`, `:require`.
`path` is the chain of manifest addresses from the root to the module the
blob belongs to.

The closure of one manifest is, in walk order:

| Step | Blob | Check before continuing |
|---|---|---|
| 1 | The manifest | `segment-matches?`; `manifest-defect` is nil. |
| 2 | Every row of `:yin.module/tree` | A bounded worklist over the grammar's child slots (`ast-format`'s `:parts-fn`); each row `segment-matches?` and has no `row-local-defect`. |
| 3 | The derivation record of each of `:yin.semantic/code`, `:yin.debruijn.code`, `:yin.debruijn.register` the manifest names | `segment-matches?`; `record-defect` is nil; `:yin.ledger/input` equals the tree. |
| 4 | The image each record's `:yin.ledger/output` identifies | Its storage address is the identity itself for the semantic vector, and the `:yin.module/index` entry for H and R; an identity with no index entry is `:invalid`. The image satisfies its format record's `:identity-matches-fn`. |
| 5 | The closure of every manifest under `:yin.module/requires` | Recursively, depth bounded; a manifest already visited is not walked again. |

- **All four formats are walked, whichever one a request names.** A closure
  is complete only when the tree and all three lowered images the manifest
  names are local. A manifest that names fewer derivations has a smaller
  closure; that is the publisher's choice and section 5.2 forbids it to this
  epic's publisher.
- `:yin.module/primitives` values are profile addresses compared at
  discharge. They are not fetched and are not part of the closure.
- The first absent blob ends the walk as `:missing`. The first failed check
  ends it as `:invalid`. `:invalid` is terminal; fetching again cannot
  repair content that hashes to its address and is wrong.
- The walker verifies shape and address. It does not re-lower, discharge or
  judge names: `link-manifest` does, afterwards, against the same local
  store.

### 4.3 The staged load

`dao.space.dht/load` generalizes `load-index`. `dao.space.dht` learns nothing
about `yin.*`: the walk is an argument.

```clojure
(dao.space.dht/load node address {:kind k :walk f})   ; -> node
(dao.space.dht/load-status node address)              ; -> status or nil
(dao.space.dht/forget node address)                   ; -> node
```

`f` is `(fn [handle] outcome)`, called with the node's local store, and
answers one of three shapes:

```clojure
{:dao.space.dht/walk :missing  :address a}
{:dao.space.dht/walk :complete :value v}
{:dao.space.dht/walk :invalid  :address a-or-nil :defect {:code code ...}}
```

- A defect is a map with a keyword `:code` from the walk's own closed set.
  The closure walk's set is section 4.2's; the index walk's is
  `:index-invalid`. A defect may carry `:detail` (data) and `:text` (a
  diagnostic string); no consumer compares either.
- A function that throws is `:invalid` with the one defect
  `{:code :dao.space.dht/walk-threw :text <the exception's message>}`. The
  code is the contract; the text differs by host and is never matched.
- An answer of any other shape is `:invalid` with
  `{:code :dao.space.dht/walk-shape}`.
- `load-index` is `load` with `:kind :dao.space.dht/index` and a walk that
  adapts `index-datoms`; its behaviour, events and tests are unchanged.
- On `:missing` the node requests the address with one `:jing/get` through
  its `dao.jing.content.step` client and holds one outstanding fetch per
  load. A fetched payload is verified against the requested address before
  it enters `:local` (`dao.jing/accept-bytes!`, `dao.jing.dht.md` 4.5), and
  the walk re-reads and re-checks it.
- **Asking.** `dao.jing.content.step/request-get` answering `:busy` (the
  client still owes an unsent request) is not a failure: the load stays
  `:loading` with nothing fetching and asks again on the next step. Any
  other outcome that submitted nothing fails the load `:unaskable` with
  that outcome keyword verbatim.
- A load is `:loaded` only when its walk answered `:complete`.
- A load already `:loading`, `:loaded` or `:failed` is left as it is.
  `forget` removes a terminal record; it is refused for a `:loading` one.

**Load status:**

```clojure
{:status :loading :kind k :fetched n :fetching address-or-nil}
{:status :loaded  :kind k :fetched n :value v}
{:status :failed  :kind k :fetched n :reason reason}
```

**Failure reasons are data.** S5's strings are replaced, with no
compatibility path:

| `:reason` | Meaning |
|---|---|
| `{:dao.space.dht/failure :miss :address a :cause c}` | No peer produced `a`. `c` is the `/miss` fact's reason for that address (`/exhausted`, `/deadline`, `/solo`, `/busy`), or `:dao.jing.dht/gap` when the fact was lost. |
| `{:dao.space.dht/failure :invalid :address a-or-nil :defect {:code c ...}}` | The walk refused local content, threw, or answered another shape. |
| `{:dao.space.dht/failure :unaskable :address a :outcome o}` | The request for `a` could not be submitted; `o` is the client's outcome keyword. |

These three are the whole set. Each has exactly one link response shape
(section 9).

**Events** (from `step`, each a plain map under `:dao.space.dht/event`):

```clojure
{:dao.space.dht/event :loaded      :manifest a :kind k :fetched n ...}
{:dao.space.dht/event :load-failed :manifest a :kind k :reason reason}
```

An index load's `:loaded` event keeps `:datoms`. Each load emits exactly one
terminal event. Rendering reasons as text is the REPL's (`yin.repl.dht`).

A module load is `load` with `:kind :yin.module/manifest` and the walker of
4.2. Not found from a DHT is not authoritative absence: a failed load says
no reachable candidate produced a verifiable value in time, and a new
require may try again.

## 5. Publication

### 5.1 Rows, every round (owner decision 1)

The code indexer (`yin.repl.index`) materializes every row of each expanded
program packet it commits, through `yin.vm/materialize-tree!`, into the
index store, in the same round as that packet's transaction and before the
round's publication is announced.

- The rows of a round belong to that round's publication: they are put
  through the node's store and are in the announced window.
- A packet the indexer did not commit materializes nothing.
- A row put that fails is recorded under the indexer's `:failure` with
  stage `:materialize`; the round is not reported indexed.
- **Recovery.** The committed program whose rows were not written stays
  in the indexer's unwritten set. Every later round that commits, and
  every name transaction, writes the unwritten rows first, oldest first,
  and publishes only when none remain: no HEAD write and no announcement
  is made while a committed program's row is missing. Once they are all
  written the `:materialize` failure is cleared and that round's
  publication covers every committed transaction; the REPL says the index
  caught up. The unwritten set is process state: it survives `(reset)`
  over a durable store, and not a restart.
- Restored history is not re-materialized on rehydration. An index
  published before this slice names rows its store does not hold, and
  loading one such program by tree address is `:absent`.

**The banner changes.** With `--dht-publish` the REPL states that the code
index *and the code itself* (every evaluated program's rows) are shared.

**What the address fact promises.** An `:yin/address` fact names content the
publisher's own store holds from the round that committed it, and that a
publishing node serves to any peer that asks it. That much holds for every
round, whatever the network did. Retrieval from peers other than the
publisher is promised only by a publication whose result is `:acknowledged`
(section 5.5), and a result that is not names every address it does not
cover.

A round now puts one blob per row, far more than the DHT's pending-write
bound. Section 5.5 fixes how those writes are paced, bounded and reported.

### 5.2 A module closure

`yin.vm.linker.publish/publish-module!` is the one manifest publisher. It is
plain Clojure and host neutral, and takes any `dao.jing` handle.

```clojure
(publish-module! handle
  {:name 'my.lib
   :ast ast                       ; the canonical map AST
   :exports #{'f 'g}
   :requires {'other.lib manifest-address}
   :primitives {'+ profile}})     ; yin.vm/profile-of's answer for the name
;; ->
{:address manifest-address
 :manifest {...}
 :identities {:yin.semantic/code v :yin.debruijn.code H :yin.debruijn.register R}
 :links {:yin.ast/code outcome :yin.semantic/code outcome
         :yin.debruijn.code outcome :yin.debruijn.register outcome}}
```

- It materializes the tree rows, the semantic vector, the stack image, the
  register image, one derivation record per lowered format under the pinned
  profile (`ledger/lowering-profile`, `linker/stack-lowering-profile`,
  `linker/register-lowering-profile`), and the schema-1 manifest with
  `:yin.module/index` naming H and R.
- **It always mints all four formats.** A manifest from this publisher has a
  full closure.
- **It links what it published.** After minting, it walks the closure
  (must be `:complete`) and links each format locally under `:verifying`
  with discharge deferred. `:links` carries each outcome: `{:status :ok}` or
  the linker's refusal. A publisher learns before any peer does which
  backends can link the module.
- It refuses, writing nothing, when `:exports` names a symbol the tree does
  not define at module level (`:yin.link.publish/undefined-export`).
- Publishing twice writes nothing new: every blob is content-addressed.
- The manifest's `:yin.module/primitives` holds each declared name's
  `:yin.k/profile` address. `:yin.module/footprint` is computed, never
  supplied.

**The footprint.** `(yin.vm.linker.publish/footprint handle spec)` is a pure
function of three inputs and answers the manifest's
`:yin.module/footprint`:

| Input | What is read |
|---|---|
| The module tree | `(yin.vm/ast-requirements db)` over the `$ast` relation of the tree's rows, `(vals (:rows (yin.vm/ast->semantic-bytecode ast)))`: its `:store-keys`, `:effects`, `:ffi-ops` and `:parked-ids`. |
| `:primitives` | Each declared profile's `:yin.k/effects`. |
| `:requires` | Each required manifest, read from `handle`: its own `:yin.module/footprint` `:effects`. |

```clojure
{:store-keys <the tree's :store-keys>
 :effects    <the tree's :effects
              ∪ every declared primitive's :yin.k/effects
              ∪ every required manifest's footprint :effects>}
```

- `:store-keys` is the module's own: every constant store key its tree
  reads, writes or defines, exactly the set `ast-requirements` answers. A
  dependency's keys are not included; each module has its own store
  (`yin.vm.linker.md` 7.3).
- `:effects` is closed over dependencies by induction: a required
  manifest's footprint already holds its own dependencies' effects.
- A required manifest absent from `handle` refuses
  `:yin.link.publish/missing-requirement` naming it. A declared primitive
  with no profile refuses `:yin.link.publish/unprofiled-primitive`.
- A tree whose requirements hold any `:ffi-ops` or `:parked-ids` refuses
  `:yin.link.publish/ffi-op` or `:yin.link.publish/parked-id`. A schema-1
  manifest has no key that declares either, so a receiver could not learn
  of them.
- Every function this reads exists on master. The footprint is not gated on
  any unfinished work.

### 5.3 Deriving a module from indexed code (R9)

`yin.vm.linker.publish/module-from-index` builds `publish-module!`'s
argument from a queryable index and a name. It persists nothing.

```clojure
(module-from-index db {:name 'my.lib :exports '[f g]
                       :primitives vm/primitives :modules linked-modules
                       :host-modules host-module-names})
;; -> the publish-module! spec, or the section 9 refusal
{:status :refused :reason reason ...}
```

`db` is a relation of the index's `[e a v t m]` datoms. `:modules` is
`{name manifest-address}`, derived from the session's module registry (a
linked entry carries its manifest address); `:host-modules` the names of
its host modules.

- **Defining program.** The defining program of a symbol is the indexed
  program, greatest `t`, whose tree holds a `yin/def` application with that
  literal symbol at module level. A symbol with none is
  `:yin.link.publish/undefined-export`.
- **Closure by definitions.** Start from the exports' defining programs. A
  free name of the collected programs that some indexed program defines
  adds that name's defining program. Repeat to a fixed point.
- **Closure by linked requirements.** For each linked module declared as a requirement by a free qualified name in a collected program, also collect the latest indexed program, by `t`, whose module-level form requires that module. Repeat definition and requirement collection to a fixed point. Sequence every collected program once in ascending `t`. If no such requiring program exists, refuse publication with `:yin.link.publish/missing-require-program`, naming the module. The manifest still pins the module address derived from the session’s linked module registry; the index does not declare reader principals.
- **One tree.** The module tree is the collected programs in ascending `t`,
  each once, sequenced in that order. Whole programs are taken; a
  definition that is not exported is module-internal. An empty export
  list collects no program. Its tree is the canonical no-op tree
  `yin.vm.linker.publish/no-op-tree`, a single `nil` literal that defines
  nothing. That module is published and linked like any other; deriving
  it never throws.
- **Free names after closure.** A remaining free name must be a primitive
  of `:primitives` (declared under `:yin.module/primitives` with its profile
  address) or a name qualified by a module in `:modules` that the session
  linked through the linker (declared under `:yin.module/requires` with
  that module's manifest address). Anything else refuses
  `:yin.link.publish/undeclared-free` naming it.
- A name bound by a host module refuses `:yin.link.publish/host-module`.
  No host function arrives by linking.
- The footprint is `publish-module!`'s (section 5.2), computed from the
  derived tree, the declared primitives and the required manifests.
  `module-from-index` supplies none.

**What links where.** Step 5a of `yin.vm.linker.md` is unchanged by this epic. A module whose exported lambda reads another module-level definition is published with `:links` showing the tree format refused (`:undeclared-free`, naming the read) and the semantic, stack and register formats ok, exactly as the starting tree behaves. Such a module loads and evaluates on the semantic, stack and register VMs. `:links` is the linker's own verdict per format; the publisher never restates it. The publisher prints which.

### 5.4 Announcing

`(yin.vm.linker.dht/publish! node spec)` is `publish-module!` on
`(dao.space.dht/store node)`. The blobs join the node's current publication
window and are reported by the announcement that closes it. A module
published at the REPL is announced by the round's HEAD write, with the
name assertion of section 6 in the same index.

### 5.5 Replication: the ledger, the backlog, repair and the result

The node's store handle no longer asks the DHT to replicate at the moment of
a put. A put inserts into `:local`, records the address on the node's ledger
ring, and returns the local verdict. Oversize is still refused before the
local insert. `step` owns everything after that. No round waits, as before.

#### 5.5.1 The publication ledger

A **publication** is the set of distinct addresses put between two
announcements, closed by the announced manifest. The node keeps one ledger
per live publication: every address of the publication, each in exactly one
of three states.

| State | Meaning |
|---|---|
| `waiting` | No outcome yet: the address is queued or its request is outstanding, for the first time in this publication. |
| `sent` | A `/sent` fact arrived while this publication held the address. Never leaves this state. |
| `failed` | The last outcome was not `/sent`. Carries that reason. May become `sent` by a later repair. |

- The ledger is **complete and fixed**: `:blobs` is its size and never
  changes. Repair updates this ledger; it never creates another publication
  for the same manifest.
- A publication is **live** from its announcement until it is retired.
  It is retired when it is `:acknowledged`, when its repair ends (5.5.4),
  or when the node closes.
- **After the first report, every blob is `sent` or `failed`.** A failed
  blob whose repair try is queued or outstanding stays `failed`, with its
  last reason, until a `/sent` arrives. So in every event of a publication
  `:sent + (count :failed) = :blobs`.

**Shared addresses.** One address has at most one queue entry and at most
one outstanding request on the node, whatever number of live publications
hold it.

- When an address's request settles, the outcome is written to the ledger
  of **every live publication** that holds the address as `waiting` or
  `failed`: `/sent` makes it `sent` in each; a failure makes it `failed`
  with that reason in each.
- A publication announced while an address it holds is already queued or
  outstanding joins that one attempt.
- Nothing else is shared. An outcome recorded before a publication was
  announced does not count for it: its address is admitted and requested
  again. A write is idempotent by content address.

#### 5.5.2 The backlog

A node holds two FIFO queues of addresses awaiting a replicate request: the
**fresh** queue, for a publication's first pass, and the **repair** queue,
for retries.

- **Admission** of fresh addresses happens in `step`, when the ledger ring
  is read: each put address is appended to the fresh tail in put order.
- **Two capacities, never shared.** The fresh queue holds at most
  `:max-backlog` addresses (default 4096). The repair queue has its own
  capacity, `:max-repairing × :repair-batch` (16 × 64 = 1024 by default),
  which fresh writes can never occupy. No amount of fresh writing denies a
  repair its place, and no amount of repair denies a fresh write its place.
- **A full fresh queue.** A fresh address that arrives when the fresh queue
  is full is **not admitted**: it is `failed` at once with reason
  `:dao.space.dht/backlog-full`. Its local copy stays. Nothing is evicted to
  make room.
- **The repair queue is never full for a publication's batch.** A repairing
  publication holds at most `:repair-batch` (default 64) addresses in the
  repair queue at once, and at most `:max-repairing` publications repair,
  so the capacity above always has room for every repairing publication's
  batch (5.5.4).
- **Open publications are bounded.** At most `:max-open` (default 16)
  publications may await their first report. An announcement that would
  make one more does not wait and displaces nothing: every address of the
  new publication is `failed` at once with reason
  `:dao.space.dht/publications-full`, none is `waiting`, its first report
  is made in that step, and it continues under repair. An address it shares
  with an attempt already in progress is `failed` like the rest, and takes
  that attempt's outcome when it settles, by the shared-address rule of
  5.5.1.
- **What a node retains** is therefore bounded by composition data alone:
  at most `:max-open + :max-repairing` live publications, each ledger at
  most the ledger ring's capacity in addresses; at most `:max-backlog`
  fresh and `:max-repairing × :repair-batch` repair queue entries; at most
  `max-pending-writes` requests outstanding. `:max-repairing` bounds
  repairing publications only.
- **Release.** Each step appends replicate requests while the number
  outstanding is below the DHT's `max-pending-writes`. The node never has
  more outstanding than that bound, so its own writes never draw `/busy`.
- **Fairness between the queues.** While the repair queue is non-empty,
  `:repair-slots` (default 16) of the outstanding bound are reserved for
  it: fresh requests hold at most `max-pending-writes − repair-slots`
  outstanding, repair requests at most `:repair-slots`. While the repair
  queue is empty, fresh requests may use the whole bound. Neither queue can
  starve the other, and repairs never take more than their share from a new
  publication.
- **Order.** Each queue is strictly FIFO. Fresh order is put order, so an
  older publication's first pass precedes a newer one's. No publication is
  prioritized.
- **Outcomes.** One request has one outcome. `/sent` is `sent`. Every
  `/unacknowledged` reason is `failed` with that reason. There is no
  immediate second try: a retry is a repair (5.5.4).
- **A solo or non-publishing node holds no backlog.** Every put is `failed`
  at admission with `/solo` or `/unpublished`.
- **A lost ledger-ring entry** (more puts in one window than the ring
  holds) or a facts gap reports `:publication-unknown`, as today, and that
  publication is not live.

#### 5.5.3 The result and the events

```clojure
{:dao.space.dht/event :published | :republished
 :manifest m
 :result :acknowledged | :partial | :unacknowledged
 :blobs n            ; the ledger's size; constant for the publication
 :sent s             ; ledger entries sent
 :peers p            ; fewest peers any sent blob reached; absent when s = 0
 :failed [{:address a :reason r :peers k} ...]   ; every entry not sent
 :repairing? b       ; true while the node will retry this publication
 :ended reason}      ; only when repair ended without acknowledgement
```

| `:result` | Condition, over the whole ledger | What it promises |
|---|---|---|
| `:acknowledged` | Every blob sent. | Every address of the publication was handed to at least `ack-peers` peers. |
| `:partial` | The manifest blob sent; at least one other blob not. | Peers can find the publication. The addresses under `:failed` are held and served by the publisher only. |
| `:unacknowledged` | The manifest blob not sent. | Nothing beyond the local copy. `:failed` still lists every blob not sent. |

- The result is always computed over the **whole ledger**. A blob sent in
  the first pass and a blob sent by a repair count alike. A publication
  whose manifest was sent in the first pass and whose rows were sent by
  repair is `:acknowledged`.
- `:failed` is complete, in address order.
- `:acknowledged?` is removed, with no compatibility path.

**Which event, when.** A publication produces:

| Event | When | Times |
|---|---|---|
| `:published` | The **first report**: every blob has left `waiting`. | Exactly once. |
| `:republished` | After the first report, the `:result` changed (to `:partial` or to `:acknowledged`), **whatever wrote the ledger**, or repair ended. A change that ends repair is one event. | Zero or more; at most two result changes and one end. |

- **The cause of a change does not matter.** A ledger entry of a reported
  publication is written by its own repair request, or by the fact of a
  request it shares with another publication (5.5.1): a fresh request
  another publication issued, or another publication's repair. In every
  step that wrote to a reported publication's ledger, the node recomputes
  its result and whether its repair has ended, and emits `:republished` if
  either changed. No result ever changes without an event.
- This covers a publication reported `:dao.space.dht/publications-full`
  (5.5.2) that shares an address whose request was already outstanding:
  when that request's `/sent` arrives, the entry becomes `sent`, and if the
  result is now `:partial` or `:acknowledged` the publication is
  `:republished` in that step, before any repair cycle of its own.
- `:repairing?` is true in an event exactly when the result is not
  `:acknowledged` and repair has not ended.
- A step that changes ledger entries but neither the result nor whether
  repair has ended produces **no event**. A publication that never
  improves is silent after its first report.
- **Final acknowledgement** is the one `:republished` (or the first
  `:published`) whose `:result` is `:acknowledged`. Nothing follows it.
- `:ended` is one of `:dao.space.dht/terminal` (every remaining failure is
  terminal), `:dao.space.dht/cancelled`, `:dao.space.dht/displaced` (5.5.4).

#### 5.5.4 Automatic repair (owner decision 5: "Automatic retry while open")

A publication whose first report is not `:acknowledged` is repaired by the
node, in the background, for as long as the node is open.

- **What is retried.** A `failed` blob whose reason is **retryable**:
  `/too-few-peers`, `/busy`, `:dao.space.dht/backlog-full`,
  `:dao.space.dht/publications-full`. A blob that is `sent` is never sent
  again.
- **What is not.** A **terminal** reason is never retried: `/solo`,
  `/unpublished`, `/oversize`, `/absent`, `:dao.space.dht/cancelled`. A solo
  or non-publishing node therefore repairs nothing: its composition cannot
  change while it is open.
- **A repair cycle.** Each repairing publication has a due reading, in the
  node's own ticks. At a step whose reading has reached it, a cycle
  **opens**: it offers every retryable failed blob the publication holds at
  that moment, in address order.
- **The admission turn.** In every step, repair admission runs **before**
  fresh admission, and does not depend on it. For each publication with an
  open cycle, oldest publication first, offered blobs not yet admitted are
  appended to the repair tail, in address order, until the publication
  holds `:repair-batch` addresses in the repair queue. An offered blob that
  is already queued or outstanding through another publication counts as
  admitted. The cycle **closes** when every blob it offered has been
  admitted once and has settled.
- **Every due repair is requested.** The repair queue's capacity is its
  own, a publication's batch always fits, and `:repair-slots` requests are
  reserved while the repair queue is non-empty. A request ends by its fact
  or by the DHT's `ack-ticks` deadline. So each offered blob of each
  repairing publication receives a replicate request within a bounded
  number of steps and ticks, whatever the fresh queue holds and however
  long fresh writes continue.
- **Pacing.** The first cycle is due `:repair-ticks` (default 30000) after
  the first report. The next is due after the cycle closes: after a cycle
  in which no blob of the publication was sent, the delay doubles, up to
  `:repair-max-ticks` (default 600000); after a cycle in which one was, it
  returns to `:repair-ticks`. A tick gap makes a cycle late, never wrong.
- **Bounds.** At most `:max-repairing` (default 16) publications repair at
  once. When one more needs repair, the oldest is retired with
  `:ended :dao.space.dht/displaced`. With the bounds of 5.5.2, a node that
  can reach nobody retains a bounded number of ledgers, queue entries and
  requests, and says nothing after each first report beyond one
  displacement event per publication retired.
- **Repair ends** in exactly four ways:
  1. the result becomes `:acknowledged` (the final event; no `:ended`);
  2. no retryable failure remains and the result is not `:acknowledged`
     (`:ended :dao.space.dht/terminal`). When this already holds at the
     first report, that `:published` event carries it and
     `:repairing? false`;
  3. `cancel!` (5.5.5);
  4. displacement.

  And silently when the node closes: `close!` discards every ledger and
  both queues and reports nothing. Local copies are durable. A node opened
  again over the same directory repairs nothing from before; the ledger is
  process state and is not rebuilt.
- **A manual nudge.** `(dao.space.dht/retry! node manifest-address)` makes
  that publication's next cycle due now and resets its delay. It changes
  no rule above and reports nothing itself. It is refused for a manifest
  that is not repairing.
- `busy?` is true while a first report is owed or any address is queued or
  outstanding. A publication waiting for its next cycle does not make the
  node busy.

#### 5.5.5 Cancellation

`(dao.space.dht/cancel! node manifest-address)` ends a live publication now.
It is refused for a manifest with no live publication.

Every ledger entry is settled for **this publication** at the moment of the
call:

| Entry at the call | Becomes | The write itself |
|---|---|---|
| `sent` | Stays `sent`. | Done. |
| `failed`, nothing outstanding | `failed` with reason `:dao.space.dht/cancelled`; the earlier reason is kept under `:was`. | Its queued repair entry, if any, is removed, unless another live publication holds the address. |
| `waiting`, queued | `failed` with reason `:dao.space.dht/cancelled`. | Its queue entry is removed, unless another live publication holds the address. |
| `waiting` or `failed`, **request outstanding** | `failed` with reason `:dao.space.dht/cancelled`. | The request cannot be withdrawn. It runs to its fact. |

- **Cancellation does not wait.** The publication is reported by the next
  step, as `:published` if its first report had not been made and as
  `:republished` otherwise, with `:repairing? false` and
  `:ended :dao.space.dht/cancelled`, and it is retired. Its counts are
  those of the table, so `:sent + (count :failed) = :blobs`.
- **A late fact never changes a reported cancellation.** The fact of an
  outstanding write that a cancelled publication held is applied to the
  other live publications that hold the address, by the shared-address
  rule, and to nothing else. The cancelled publication is retired and its
  event is not amended.
- **`cancelled` means not counted, not unsent.** A blob reported
  `:dao.space.dht/cancelled` whose request was outstanding may have reached
  peers. The result under-claims and never over-claims.
- An address another live publication holds keeps its one queue entry and
  its one request; that publication's accounting is untouched.
- Nothing cancels a publication implicitly. A newer round does not
  supersede an older one's writes.

#### 5.5.6 What the result means for the index and for readers

- **The announced index is unaffected.** HEAD moves when the round's blobs
  are local and the manifest reads back, before any network result, exactly
  as in S5. A `:partial` or `:unacknowledged` result retracts nothing.
- **Effect on a reader.** A reader that loads an address under some
  publication's `:failed` reaches it only if its lookup asks the publisher.
  Otherwise its load fails `:miss` with the DHT's cause, as data. It never
  loads wrong content and never hangs. Once repair sends the blob, a new
  load succeeds.
- The REPL prints the first report: the result and, when it is not
  `:acknowledged`, the count of failed blobs, the first reason, and whether
  the node is retrying. It prints each `:republished` the same way. Its
  `dao.space.dht` host module exposes `retry` and `cancel` over the two
  plain functions.
- **A module published at the prompt** shares its round's publication. Its
  name assertion is in the index whether or not its closure replicated; a
  reader of a `:partial` publication may resolve the name and fail the load
  with a `:miss`, until repair completes.

## 6. Names: envelopes, proofs, keys

### 6.1 The envelope

The envelope shapes are `yin.vm.linker.md` 8.2's, with the key set closed
and the principal fixed:

```clojure
;; assertion
{:yin.module/op          :assert
 :yin.module/name        'my.lib
 :yin.module/manifest    :segment/...
 :yin.module/asserted-by "ed25519:<64 lowercase hex>"
 :yin.module/seq         41}

;; retraction
{:yin.module/op          :retract
 :yin.module/of          :segment/...        ; segment-key of the assertion
 :yin.module/asserted-by "ed25519:<64 lowercase hex>"
 :yin.module/seq         42}
```

- No other key is written. `:yin.module/issued-at` is not written: the
  publisher owns no clock.
- An envelope's id is its `dao.jing/segment-key`.
- Envelopes enter the index as one transaction of datoms
  `[ev :yin.module/envelope env]` and `[ev :yin.module/proof proof]`, one
  entity per envelope, with the session's metadata entity in `m`.

### 6.2 The principal id and key encoding

- A principal **is** its public key: the string `"ed25519:"` followed by the
  64 lowercase hexadecimal characters of the 32-byte Ed25519 public key
  (RFC 8032 encoding). No name, hash or registry stands between them.
- A public key in a declaration or a flag is the same 64-character hex.
- A signature is the 64-byte RFC 8032 signature as 128 lowercase hex
  characters.
- A secret is the 32-byte RFC 8032 seed as 64 lowercase hex characters.
- Any other length, any uppercase character, or any non-hex character is
  refused. Decoding is strict.
- A human label for a principal is local composition data (a petname). It
  never enters an envelope.

### 6.3 The proof and the signed bytes

```clojure
{:yin.module/signature "<128 lowercase hex>"}
```

The signed message is exactly:

```text
UTF-8("yin.module/envelope:v1\n") || dao.jing/canonical-bytes(envelope)
```

- The prefix is domain separation: the key signs nothing else under this
  prefix, and a signature made for another purpose is never an envelope
  proof.
- `canonical-bytes` is `dao.jing`'s deterministic CBOR. The same envelope
  yields the same bytes on every host.
- Ed25519 is pure (no prehash), per RFC 8032. Signing is deterministic: one
  key and one envelope have one signature.
- Verification is synchronous on every host. The fold calls it as
  `(verify key canonical-bytes signature)`; the prefix is applied inside the
  verify function, so `yin.vm.linker.authority` is unchanged.
- Verification refuses, as `:bad-proof`: a signature that does not verify, a
  malformed key or signature, and an envelope whose
  `:yin.module/asserted-by` is not `"ed25519:"` plus the declared key.
- **A change of `dao.jing`'s canonical encoder invalidates every existing
  signature.** Envelopes are then re-signed by their principals. No
  migration path exists and none is promised.

A reader's declaration for one principal:

```clojure
{"ed25519:<hex>" {:proof :yin.module/signature
                  :key "<64 hex>"
                  :verify yin.vm.linker.sign/verify-envelope
                  :seq-floor 0}}
```

### 6.4 `yin.vm.linker.sign`

| Function | Meaning |
|---|---|
| `(generate)` | A new key `{:seed hex :public hex}` from the host CSPRNG. |
| `(public-of seed)` | The public key of a seed. |
| `(principal public)` | `"ed25519:" + public`. |
| `(sign-envelope seed envelope)` | The proof map of 6.3. |
| `(verify-envelope public canonical-bytes signature)` | True or false; never throws. |
| `(key-text key)`, `(key-from-text s)` | The key file's content, both ways; `key-from-text` refuses with a reason. |

The primitive behind it is a host library, never hand-rolled: `java.security`
(`Ed25519`, JDK 17 and 21) on the JVM; `crypto.sign` and `crypto.verify`
with `ed25519` keys on Node, required at run time so a browser build
compiles; a synchronous pure-Dart Ed25519 package on Dart, pinned in
`pubspec.yaml`. An asynchronous-only package is not admissible. A host
without the primitive (a browser) refuses `sign-envelope` and
`verify-envelope` is false: names do not resolve there, and direct addresses
still link.

**Vectors.** `test/yin/vm/linker/sign_vectors.edn` is committed in L0 and
every host asserts it byte for byte:

- RFC 8032 section 7.1 TEST 1, TEST 2 and TEST 3: seed, public key, message,
  signature.
- One assertion envelope and one retraction envelope under TEST 1's seed:
  the envelope, its canonical bytes in hex, the signed message in hex, the
  signature, and the envelope id.
- Tamper cases, each `:bad-proof`: a changed name, manifest, sequence,
  operation or principal; one flipped signature bit; another key; a
  signature made without the prefix.

### 6.5 The key file (owner decision 2)

A publisher's key is loaded from a file named at startup
(`--dht-key <file>`, or `:key` to `yin.vm.linker.dht` functions). The file is
one EDN map:

```clojure
{:version 1
 :algorithm :ed25519
 :seed "<64 lowercase hex>"
 :public "<64 lowercase hex>"}
```

- `:public` must equal `(public-of seed)`; a mismatch, another version,
  another algorithm, an extra key or a missing key refuses startup with the
  reason. Nothing falls back to a fresh key.
- A named file that does not exist refuses startup. A key is created only
  by an explicit act: `--dht-keygen <file>` writes a new key file and exits,
  and refuses to overwrite an existing file.
- The file is written owner-readable only where the host can set that.
  The JVM creates it `rw-------` on a POSIX file system; Node creates it
  with mode `0600`. **Dart cannot**: `dart:io` sets no file permissions, so
  the Dart keygen creates the file as the process umask allows and prints
  a warning naming `chmod 600 <file>`. The operator must protect the key
  file on such a host — `chmod 600` it at once, or create it under
  `umask 077` or in a directory only its owner can read — and on any
  host where the file system ignores POSIX modes. Anyone who can read the
  file holds the principal (the disclosed-key row of the table below).
- The seed is never printed, logged, written to the index, or sent. The
  banner prints the principal.
- Without a key a node publishes no name: `publish` refuses
  `:yin.link.publish/no-key`. It still reads, resolves, loads and links.
- A node with a key declares its own principal in its own authority, so it
  resolves the names it published.
- Tests use `(generate)` and hold the key in memory.

**The sequence is derived.** A publisher's next `:yin.module/seq` is one
more than the greatest sequence among its own principal's envelopes in its
own index, and 1 when there are none. It is never stored beside the key.

**Loss and replacement:**

| Event | Consequence | Remedy |
|---|---|---|
| The key file is lost. | That principal can never sign again. Its assertions stand wherever a reader still declares it, and can never be retracted. | Generate a new key: a new principal. Publish again under it. Every reader replaces the old declaration with the new one. |
| A reader declares both the old and the new principal. | A name both bind to the same manifest resolves (R8). A name they bind to different manifests is `:ambiguous-name`. | The reader removes the old declaration. |
| The key is kept and the index directory is lost. | The derived sequence restarts at 1. Two distinct envelopes then share a sequence; a reader folding an old and a new snapshot sees equivocation and discards that principal from that sequence on. | Hydrate the directory from the last published index manifest before publishing, or use a new key. |
| The key is disclosed. | Anyone holding it can bind names for that principal. Nothing in this epic revokes a key. | Every reader removes the declaration; the publisher takes a new key. |

### 6.6 Republishing a name

The fold has no recency rule: two assertions by one principal for one name
with different manifests are `:ambiguous-name`. Publishing a name its
principal already binds to another manifest therefore writes, in one
transaction, a retraction of the standing assertion and the new assertion,
at consecutive sequences. Publishing a name already bound to the same
manifest writes nothing.

## 7. Resolution

### 7.1 Authority is the reader's composition

- The declared principals come from the reader's composition:
  `--dht-principal <64 hex>` (repeatable), or `:authority` to
  `yin.repl/create-state` and to the plain functions. They are never read
  from an index.
- An index manifest a reader chose to load is a **scope of considered
  claims**. It authenticates nothing: content addressing proves which
  snapshot was loaded; signatures and declarations decide whose claims
  count.
- ShiBi is a tuple space, and later it may supply declarations and write
  capabilities as composition inputs. A tuple read from a candidate index
  never establishes its own authority.

### 7.2 The snapshot set

A node's snapshot set is: the manifest its directory's HEAD names, when
there is one, and every manifest whose `:dao.space.dht/index` load is
`:loaded` on that node. Nothing else in the local store is considered.

- Loading an index is not a subscription. A publisher's later HEAD reaches a
  reader only when its address is handed over and loaded.
- The set is recorded as the sorted vector of its addresses. That vector is
  the fold's `:snapshot` and appears in provenance.

### 7.3 The fold

```clojure
(yin.vm.linker.dht/names node authority)
;; -> authority/name-environment's answer, its diagnostics split:
{:names {name entry} :diagnostics [...] :global-diagnostics [...]
 :honored-seq {...} :snapshot [...]}
```

- For each snapshot, the envelope and proof datoms are read by query from
  that index, and `authority/events-from-datoms` assembles them **per
  snapshot**: an entity id is local to its index and proofs are joined only
  within it.
- The events of all snapshots are concatenated in snapshot order and folded
  once by `authority/name-environment`. An envelope present in several
  snapshots is one event (deduplication by content id).
- Every declared principal's `:seq-floor` is 0 unless the composition
  declares another.
- One distinct manifest address resolves the name, whatever number of
  declared principals assert it; provenance names them all (R8). More than
  one distinct address is `:ambiguous-name` naming every address and
  asserter. None is `:absent`.
- **A dangling retraction is a global diagnostic** (owner decision 6).
  - Scope: a dangling retraction whose target is not an assertion in the set. Either the target is in no snapshot, or it is another retraction.
  - Why it cannot be per-name: the envelope carries only the target's id, and only an assertion carries a name, so the reader cannot tell which name it would retract.
  - What the reader gets: the diagnostic is under `:global-diagnostics`, once. It carries `:principal`, the retraction's principal, and `:of`, the assertion id it names. It is in no per-name diagnostic. `resolve-name` and `dependency-bindings` never carry it, and `(yin.link/names)` returns it as part of this answer.
  - Retraction inside the set: when the referenced assertion is in the set, for example another principal's assertion that this principal cannot retract, the diagnostic names that assertion's name and stays under `:diagnostics`.
  - The envelope is unchanged.
- The answer is a pure function of the snapshot vector and the authority.
  A composition may keep it and recompute when either changes.
- A direct entry (`:name-env {'my.lib address}`, `--link-name
  my.lib=<address>`) is the composition as sole asserter. It wins over the
  fold for that name and its provenance says `:composition`.

### 7.4 Transitive requires

A module's `:yin.module/requires` pins each dependency's manifest address,
and discharge accepts only a linked module of that address. The closure
walker loads the pinned closure. The install child still requires the
dependency **by name**. So each dependency name must resolve, in the
reader's own name environment, to the address the publisher pinned.

**A dependency name is resolved exactly as any name is: by the fold of 7.3
over the reader's snapshot set, under the reader's declared principals.**

- No index is privileged. The dependency's assertion may be by any declared
  principal, in any snapshot of the set. The dependent module's index need
  not hold it, and a publisher asserts only the names it publishes.
- Nothing is copied. A publisher does not relay, re-sign or re-assert
  another principal's envelope, and a pinned address in a manifest is never
  a name claim: it says what the publisher linked against, not who may bind
  the name.
- A dependency published by another principal therefore reaches a reader in
  one way: the reader is handed that principal's index manifest address and
  loads it (`load-index`), and declares that principal
  (`--dht-principal`). Both are the reader's composition, as for any name.
- A direct entry (`:name-env`, `--link-name`) binds a dependency name like
  any other.

**The binding check.** `(yin.vm.linker.dht/dependency-bindings node authority
manifest-address)` reads the loaded closure's `:requires` (section 4.2) and
answers one entry per requirement, in walk order:

```clojure
{:module requiring-manifest :name 'other.lib :pinned a
 :binding :ok}
{:module requiring-manifest :name 'other.lib :pinned a
 :binding :absent    :diagnostics [...]}
{:module requiring-manifest :name 'other.lib :pinned a
 :binding :ambiguous :addresses [...] :asserters [...]}
{:module requiring-manifest :name 'other.lib :pinned a
 :binding :mismatch  :resolved b :asserters [...]}
```

- `:ok` is: the name resolves to exactly the pinned address. Several
  declared principals asserting that one address is `:ok` (R8).
- `:absent` is: no accepted assertion. Its diagnostics are the fold's for
  that name, so an assertion by an undeclared principal, or one in an
  index the reader has not loaded, is told apart from no assertion at all
  only by what the loaded snapshots hold.
- `:ambiguous` is: more than one distinct address among accepted
  assertions, whether or not the pinned one is among them.
- `:mismatch` is: one address, not the pinned one. A dependency its
  publisher has since republished is this case.
- The DHT link source runs the check after the load and before the link
  (section 4.1). The first entry that is not `:ok` refuses the require with
  `:yin.link.dht/dependency-binding` carrying that entry. No install child
  starts.
- The check is the early, named form of what discharge would refuse later
  as `:unresolved-free`; it adds no rule the linker lacks.

## 8. Evaluation

### 8.1 Entry

Loaded code enters a session by the flow of `yin.vm.linker.md` 7.2 and 7.3,
unchanged: the require parks on the link pair; the interpreter answers under
the request's id; the receiving task correlates, discharges obligations
against its own live state (step 5b), runs the install child, and lowers its
own bindings. This holds on all four VMs: each kernel names its format and
contract through `IModuleKernel/link-format`, and the `:fallback` policy of
`yin.vm.linker.md` 5.5 applies as composed.

### 8.2 Pending and the re-check (R11)

- A link the DHT source answers pending records, on the shell's pending
  run, the module manifest address whose load it waits on.
- `yin.repl.main/step-all` calls `yin.repl/recheck-pending` once in a tick
  if and only if that tick's node events hold a `:loaded` or `:load-failed`
  event whose `:manifest` is one a pending link waits on.
- Any other node event (a bind, a publication settling, an index load,
  another module's load) triggers no re-check and changes no `:checks`.
- A terminal load event arrives once. A re-check after it finds the record
  terminal and answers the link; a second re-check for the same address
  cannot happen without a new load.
- One re-check serves every pending request in order, so several pending
  module loads completing in one tick need one re-check.
- A typed line still re-checks, as today. `(abandon)`, the retained lines,
  the identity carry and the late-answer skip of M5 are unchanged.
- `:link-policy` is consulted exactly as `yin.repl.link-policy.md` 3.2
  says. `:manual` stays the default.

### 8.3 A request budget is not a lease (R12)

A `:jing/get` ends within the DHT's `get-ticks` or when its candidates are
exhausted. That bounds **one fetch**. It does not bound the pending run: a
closure is many fetches, a failed load may be started again by a new
require, and a link waiting on a load that never starts is not bounded at
all. Only a `:link-policy` ends a pending run without `(abandon)`. The
`:lease` policy of `yin.repl.link-policy.md` section 6 remains the designed
deadline and remains deferred.

### 8.4 Implicit network (R10)

Under a DHT link source, `(require 'x)` for a name that resolves starts a
load. The startup banner and `(help)` state it: a require may fetch code
from peers. A solo node starts the load too and fails it on the node's next
step with cause `/solo`.

## 9. Failure vocabulary

Every failure is plain data on the link response, raised to the program as
the require's error, and printed by the REPL from that data.

| Outcome | Carried data | When |
|---|---|---|
| `:absent` (name) | `:name`, `:diagnostics` for that name | The fold found no accepted assertion. Diagnostics name every discarded envelope for the name: `:unauthenticated` (`:no-proof`, `:bad-proof`), `:undeclared-principal`, `:replay`, `:equivocation`, `:dangling-retraction` (only when the retracted assertion is in the snapshot set), `:malformed-envelope`. A retraction of an assertion outside the set names no name. It is never here; it is reported once under the fold's `:global-diagnostics` (7.3). |
| `:ambiguous-name` | `:addresses`, `:asserters` | More than one distinct address. |
| `:absent` (content) | `:address`, `:cause` the `:miss` cause | The load failed `:miss`. |
| `:descriptor-defect` | `:address` (or nil), `:code` the defect's closed code, `:detail` and `:text` when present | The load failed `:invalid`: the closure walk's codes of 4.2, `:dao.space.dht/walk-threw`, or `:dao.space.dht/walk-shape`. |
| `:yin.link.dht/unaskable` | `:address`, `:outcome` the client's outcome keyword | The load failed `:unaskable`. |
| `:yin.link.dht/dependency-binding` | the section 7.4 entry: `:module`, `:name`, `:pinned`, `:binding`, and its data | A dependency name does not resolve to its pinned address. |
| `:yin.link.dht/closure-incomplete` | `:address` | A link refused `:absent` after a `:loaded` record. A defect, reported. |
| The linker's own refusals | per `yin.vm.linker/refusal-reasons` | `:contract-mismatch`, `:module-name-mismatch`, `:derivation-mismatch`, `:unverified-derivation`, `:undeclared-free`, `:use-before-definition`, `:unresolved-free`, `:shadowed-free`, `:unsupported-format`, and the rest, unchanged. |
| `:yin.repl/abandoned`, `:yin.repl/link-policy` | per the link policy | The run was abandoned. |

Each row is one exact response shape,
`{:status :refused :reason <outcome> ...carried data}`, identical on the
JVM, Node and Dart. A consumer matches `:reason` and, for
`:descriptor-defect`, `:code`; it never matches `:text`.

Publication refusals, returned by the plain functions and printed by the
REPL: `:yin.link.publish/no-key`, `/undefined-export`, `/undeclared-free`,
`/host-module`, `/missing-require-program` (carrying `:module`, the linked
module no indexed program requires; section 5.3), `/missing-requirement`,
`/unprofiled-primitive`, `/ffi-op`, `/parked-id`, and the three closure
refusals below. Each is
`{:status :refused :reason <reason> ...carried data}`.

| `:reason` | Carried data | When |
|---|---|---|
| `:yin.link.publish/invalid-requirement` | `:name` the requirement's name | The walk (4.2) of a pinned manifest under `:requires` answers `:invalid`. Raised before anything is written. |
| `:yin.link.publish/incomplete-requirement` | `:name`, `:walk` the walk's `:missing` outcome | A pinned manifest is present but its closure answers `:missing` beyond it. Raised before anything is written. A pinned manifest that is itself absent is `/missing-requirement`. |
| `:yin.link.publish/incomplete-closure` | `:address` the minted manifest, `:walk` the walk's outcome | The walk of the closure just minted is not `:complete`. Raised after the writes and before any link: `:links` is never reported for it. |

Replication outcomes arrive through the `:published` event of
section 5.5; a blob's `:reason` there is one of the DHT's `/unacknowledged`
reasons, `:dao.space.dht/backlog-full`, `:dao.space.dht/publications-full`
or `:dao.space.dht/cancelled`.

Node-call refusals. `dao.space.dht/retry!`, `cancel!` and `forget` refuse by
throwing an `ex-info` whose data carries
`{:dao.space.dht/refused code ...}`, with the code from this closed set:

| Code | Raised by | When |
|---|---|---|
| `:dao.space.dht/not-repairing` | `retry!` | No live publication of the manifest is repairing. Carries `:manifest`. |
| `:dao.space.dht/not-live` | `cancel!` | No live publication of the manifest. Carries `:manifest`. |
| `:dao.space.dht/loading` | `forget` | The load record is `:loading`. Carries `:address`. |

A consumer matches the code, never the message. The `dao.space.dht` host
module answers each one as the call's error under the same code.

## 10. The plain Clojure API

The one path, for any Clojure program:

```text
join -> load-index -> names -> load-module -> link        (reader)
join -> publish!  -> assert! -> announce!                 (publisher)
```

| Function | Meaning |
|---|---|
| `dao.space.dht/join`, `step`, `store`, `local`, `announce!`, `close!` | S5, unchanged. |
| `dao.space.dht/load-index`, `load-status`, `db`, `q` | S5; statuses and reasons per section 4.3. |
| `dao.space.dht/load`, `forget` | Section 4.3. |
| `(dao.space.dht/loaded-indexes node)` | The covered-index manifests whose load is `:loaded`, sorted by address text. `yin.vm.linker.dht/snapshots` reads the node's loads only through it. |
| `dao.space.dht/retry!`, `cancel!` | Section 5.5. Each answers the node; refusal codes in section 9. |
| `(dao.space.dht/backlog node)` | What the node retains now: `{:fresh n :repair n :outstanding n :outstanding-fresh n :outstanding-repair n :live n :open n :repairing n :ledger-entries n}`. These are queue entries, requests outstanding (in total and per queue), live publications, those awaiting a first report, those repairing, and the ledger entries live publications hold (section 5.5.2). |
| `(dao.space.dht/publications node)` | Every live publication, oldest first, each `{:manifest m :blobs n :sent s :result r :reported? b :repairing? b :delay ticks :due reading-or-nil :cycles n :cycle-open? b :repair-queued n :entries {address entry}}`. An entry is `{:state :waiting}`, `{:state :sent :peers k}` or `{:state :failed :reason r :peers k}`, with `:was` after a cancellation. |
| `(dao.space.dht/publication node manifest-address)` | The oldest live publication of that manifest in the shape above, or nil. |
| `(dao.space.dht/ack-peers node)` | The distinct peers a blob must be handed to before it is `sent`. |
| `yin.vm.linker.closure/walk` | Section 4.2. |
| `yin.vm.linker.sign/*` | Section 6.4. |
| `yin.vm.linker.publish/publish-module!`, `footprint`, `module-from-index`, `assertion`, `retraction` | Sections 5.2, 5.3, 6.1. `assertion` and `retraction` answer `{:envelope e :proof p :datoms [...]}` for a key and a sequence. |
| `(yin.vm.linker.dht/publish! node spec)` | 5.4. |
| `(yin.vm.linker.dht/publish-name! node db opts)` | `module-from-index` over the publisher's own index `db`, `publish!`, and the envelopes of 6.6 signed with `:key`: `{:status :ok :address a :links {...} :envelopes [...]}`, the envelopes for the caller to commit as one index transaction before it announces; refused `:yin.link.publish/no-key` without a key. Every refusal writes nothing. |
| `yin.vm.linker.publish/next-seq`, `name-envelopes` | 6.5 and 6.6: the next sequence derived from the publisher's own envelopes in its index, and what publishing a name writes (nothing for the standing manifest; else a retraction of each other standing assertion, then the assertion). |
| `(yin.vm.linker.dht/head node)` | The manifest the HEAD of the node's directory names, or nil (a node over no durable directory). |
| `(yin.vm.linker.dht/snapshots node)` | The snapshot vector of 7.2: HEAD and every `:loaded` index, sorted. |
| `(yin.vm.linker.dht/authority {:principals [hex ...] :name-env {...}})` | The reader's authority of 7.1: each declared public key an Ed25519 signature principal at `:seq-floor` 0. |
| `(yin.vm.linker.dht/names node authority)` | 7.3. |
| `(yin.vm.linker.dht/resolve-name node authority name)` | The name's 7.3 entry when it resolves, else its section 9 refusal: `:absent` with `:name` and that name's `:diagnostics`, or `:ambiguous-name` with `:name`, `:addresses`, `:asserters`. The DHT link source refuses with it. |
| `(yin.vm.linker.dht/load-module node manifest-address)` | Starts the closure load; answers the node. |
| `(yin.vm.linker.dht/module-status node manifest-address)` | The load status of 4.3. |
| `(yin.vm.linker.dht/load-refusal status)` | The section 9 row of a failed closure load, given the status `module-status` answers. `:miss` gives `:absent` with `:cause`. `:invalid` gives `:descriptor-defect` with `:code`, plus `:detail` and `:text` when present. `:unaskable` gives `:yin.link.dht/unaskable` with `:outcome`. It answers nil for a load that has not failed. The DHT link source refuses with this row, so there is one conversion, not two. |
| `(yin.vm.linker.dht/dependency-bindings node authority manifest-address)` | 7.4; refused `:yin.link.dht/not-loaded` unless the load is `:loaded`. |
| `(yin.vm.linker.dht/link node manifest-address format opts)` | `link-manifest` over the node's local store; refused `:yin.link.dht/not-loaded` unless the load is `:loaded`. |

`yin.vm.linker.dht` depends on `dao.space.dht` and `yin.vm.linker.*`, and on
nothing under `yin.repl`.

**The interface gate.** `yin.repl` reaches every operation above through
these functions:

- `(require 'name)` is served by `yin.repl.link` calling `names`,
  `load-module`, `module-status`, `load-refusal`, `dependency-bindings`
  and `local-runtime` + `link-manifest`.
- The `dao.space.dht` host module gains `load-module`, `module-status`,
  `retry` and `cancel`, beside `load-index`, `load-status` and `q`, as
  effects on the query call pair.
- A `yin.link` host module answers `(yin.link/publish 'name '[exports])` and
  `(yin.link/names)`.

A host-module answer function contains argument checking, one call into the
plain API, and the response. It contains no walk, no fetch loop, no fold and
no signing. A reviewer refuses a slice whose REPL namespaces implement any
of those.

## 11. Limits and defaults

| Datum | Default | Notes |
|---|---|---|
| Closure walk bounds | `yin.vm.linker/default-bounds` | Parts, depth and bytes, as the linker's own. |
| Outstanding fetches per load | 1 | As `load-index` today. |
| Replicate requests outstanding | the DHT's `max-pending-writes` (64) | Section 5.5. |
| `:max-backlog` | 4096 addresses | The fresh queue's capacity. Beyond: `:dao.space.dht/backlog-full`. Composition data to `join`. |
| `:repair-batch` | 64 addresses | Most repair-queue entries one publication holds. The repair queue's own capacity is `:max-repairing × :repair-batch`. |
| `:max-open` | 16 publications | Awaiting a first report. Beyond: the new one is `:dao.space.dht/publications-full` and goes to repair. |
| `:repair-slots` | 16 | Outstanding requests reserved for repair while its queue is non-empty. Below `max-pending-writes`. |
| `:repair-ticks` | 30000 | First repair delay, and the delay after a cycle that sent something. |
| `:repair-max-ticks` | 600000 | Ceiling of the doubling delay. |
| `:max-repairing` | 16 publications | Beyond: the oldest is retired `:dao.space.dht/displaced`. |
| `:seq-floor` | 0 | Per declared principal. |
| Link attempt budget | 64 drive rounds | `yin.repl.link/attempt-budget`, unchanged. |
| Name scope | HEAD and loaded index manifests | Section 7.2. |
| Cost of one fold | Linear in the snapshots' datoms | **Performance limit.** `names`, `resolve-name` and `dependency-bindings` read `<dir>/HEAD` from disk and rebuild the HEAD index's datoms on every fold, and every name a `require` resolves folds once. Resolution cost therefore grows with the publisher's own index size. No cache is kept; one is admissible only if invalidated on every HEAD change and every index load. |

## 12. Slices

Order: **L0 ∥ L1 → L2 → L3 → L4 → L5.** L0 and L1 own disjoint files. Before
L3, the only `yin/repl/*` sources touched are L1's rendering of load
reasons and publication results and its two host-function entries.

**L0 — publisher, signing, local runtime.** Plain Clojure, host neutral.
Files: `src/cljc/yin/vm/linker.cljc` (`local-runtime`), new
`src/cljc/yin/vm/linker/publish.cljc`, new
`src/cljc/yin/vm/linker/sign.cljc`, `pubspec.yaml` and `pubspec.lock` (the Dart Ed25519 package and its resolution),
`test/yin/repl/require_test.cljc`,
`test/yin/vm/linker_manifest_test.cljc`, `test/yin/vm/linker_test.cljc`, new
`test/yin/vm/linker/sign_test.cljc`, new
`test/yin/vm/linker/sign_vectors.edn`, new
`test/yin/vm/linker/publish_test.cljc`. Acceptance:
- The vectors of 6.4 pass byte for byte on the JVM, Node and Dart, the RFC
  8032 vectors included.
- Tampering with the envelope's name, manifest, sequence, operation or
  principal, with the proof, or with the key fails `:bad-proof` through
  `authority/name-environment`.
- `key-from-text` refuses each malformed file of 6.5 with its reason.
- `publish-module!` replaces both test helpers; the existing require and
  manifest tests pass unchanged against it.
- `:links` reports ok on all four formats for the closed corpus. For the store corpus it reports the tree format refused `:undeclared-free` naming the read, and semantic, stack and register ok.
- An undefined export writes nothing.
- The footprint of 5.2, with no prerequisite outside master: a tree that
  defines and reads store keys answers exactly `ast-requirements`'
  `:store-keys`; an effectful declared primitive and a stream tag each add
  their effects; a required manifest adds its footprint's effects and none
  of its store keys; a missing required manifest, an unprofiled primitive,
  an FFI call and a parked id each refuse with their reason and write
  nothing. The manifest it produces makes
  `yin.vm.completion` report no `:missing :footprints` for the module.
- `yin.vm.linker/local-runtime` exists and the linker tests' runtime delegates to it; no behaviour changes. `yin.repl.link` adopts it in L3.

**L1 — the staged load, the backlog, the publication result.** Files:
`src/cljc/dao/space/dht.cljc`,
`src/cljc/yin/repl/dht.cljc` (reason and result rendering only),
`src/cljc/yin/repl/query.cljc` (status shape, `retry`, `cancel`),
`test/dao/space/dht_test.cljc`, `test/yin/repl/dht_test.cljc`,
`test/yin/repl/dht_process_test.clj` (the result's printed line),
`docs/design/dao.jing.dht.md` (section 10's event list). Acceptance:
- `load-index` is `load`; every existing index-load test passes with
  reasons asserted as data.
- A walk answering `:missing` fetches; `:complete` loads; `:invalid` fails
  without any fetch. A throwing walk fails with code
  `:dao.space.dht/walk-threw` and a walk answering another shape with
  `:dao.space.dht/walk-shape`; both codes are asserted equal on the JVM,
  Node and Dart, and no test compares `:text`.
- `request-get` answering `:busy` leaves the load `:loading` and it
  completes on a later step. A client that cannot submit fails the load
  `:unaskable` with the outcome keyword, the same value on all three hosts.
- **Pacing.** One round of 500 puts against acknowledging peers is
  `:acknowledged`; the number of outstanding replicate requests never
  exceeds `max-pending-writes` at any step, and no `/busy` fact is
  produced.
- **Sustained rounds.** Eight consecutive rounds of 500 puts (within
  `:max-backlog` in total), announced faster than they drain, are each
  reported once, in announcement order, all `:acknowledged`; the backlog
  returns to empty; `busy?` then answers false. A ninth and tenth round
  announced before any drain exceed the bound: their excess blobs are
  `:dao.space.dht/backlog-full` in their first reports, which are not
  `:acknowledged`; repair then sends them and each round ends
  `:republished :acknowledged`.
- **The result.** A mesh that refuses the store of one chosen non-manifest
  blob reports `:partial` with exactly that address under `:failed`; one
  that refuses the manifest blob reports `:unacknowledged`. HEAD has moved
  in each case.
- **The invariant.** In every `:published` and `:republished` event of
  every test in this slice, `:sent` plus the count of `:failed` equals
  `:blobs`, and `:blobs` is the same in all events of one publication.
- **Repair reaches acknowledgement, from both originals.** A `:partial`
  original (manifest sent, one row refused): after the mesh heals and
  ticks pass `:repair-ticks`, exactly one `:republished` arrives with
  `:acknowledged`, `:sent` equal to `:blobs`, and the manifest's replicate
  request was issued once in total. An `:unacknowledged` original (manifest
  and rows refused): healing first the manifest's store, then the rest,
  yields `:republished :partial`, then `:republished :acknowledged`, and
  nothing after. A blob already `sent` is never requested again.
- **A peer that never accepts.** With every peer refusing or never
  answering stores, over sustained rounds and a long run of appended ticks:
  every first report is `:unacknowledged` with `:repairing? true`; **no
  event ever reports `:acknowledged`**; no `:republished` is produced
  except one `:ended :dao.space.dht/displaced` per publication beyond
  `:max-repairing`; the fresh queue never exceeds `:max-backlog` and the
  repair queue never exceeds `:max-repairing × :repair-batch`;
  outstanding requests never exceed `max-pending-writes`; repairing
  publications never exceed `:max-repairing`; the repair delay doubles to
  `:repair-max-ticks` and stays; no put ever waits; and `busy?` is false
  between cycles.
- **Retained ledgers stay bounded, measured separately.** Under the same
  dead network, with rounds announced faster than first reports arrive:
  publications awaiting a first report never exceed `:max-open`; the
  announcement beyond it is reported in the step that reads it, every blob
  `:dao.space.dht/publications-full`, `:repairing? true`, displacing
  nothing open; live publications never exceed `:max-open +
  :max-repairing`; and the total of ledger entries the node retains, summed
  over live publications, stays at or below that count times the ledger
  ring's capacity through the whole run. A case with many open publications
  sharing one queued address asserts the same total while the queue holds
  one entry.
- **Fairness of requests.** With the repair queue full of failing addresses
  and a new round arriving, the new round's first pass holds at least
  `max-pending-writes − repair-slots` outstanding and is reported while
  repairs continue; repair requests never exceed `:repair-slots`
  outstanding; with no repairs queued, a fresh round uses the whole bound.
- **Repair under sustained fresh writes.** One early publication has one
  row refused and is `:partial`. The peers then heal for that row. From
  before its first cycle is due, and without pause, fresh rounds keep the
  fresh queue at `:max-backlog`, their excess `:backlog-full`. The early
  publication's failed row is admitted to the repair queue in the step its
  cycle opens, receives its replicate request within the reserved slots
  while the fresh queue is still full, and the publication is reported
  `:republished :acknowledged`, all while fresh writes continue. The test
  fails if the repair is admitted only after the fresh queue drains.
- **Batches.** A publication with more retryable failures than
  `:repair-batch` never holds more than `:repair-batch` addresses in the
  repair queue, has every one of them requested within one cycle, and
  `:max-repairing` such publications repairing at once each make progress
  in every cycle.
- **Overflow, a shared outstanding address, then `/sent`.** With
  `:max-open` publications open and the request for address `a`
  outstanding for one of them, a further publication whose only blobs are
  its manifest and `a`, the manifest being `a`, is announced. Its first
  report, in that step, is `:unacknowledged` with `a` under `:failed` as
  `:dao.space.dht/publications-full` and `:repairing? true`. The `/sent`
  fact for `a` then arrives, well before `:repair-ticks`: in that step the
  publication is `:republished :acknowledged`, with `:sent` equal to
  `:blobs`, no second request for `a` was issued, and no repair cycle of
  that publication ever opens. The same case with a second, unshared row
  yields `:republished :partial` on the fact, and `:acknowledged` after its
  own repair sends the row. A shared fact that is a failure changes no
  result and produces no event.
- **Terminal reasons stop repair.** A publication whose only failure is
  `/oversize` or `/absent` is reported once with `:repairing? false` and
  `:ended :dao.space.dht/terminal`, and no request for it is ever issued
  again. One with a terminal and a retryable failure repairs the retryable
  one and then ends `:terminal`, `:partial`.
- **`retry!`** makes the next cycle run at the next step without waiting
  for `:repair-ticks`, produces no event of its own, and is refused for a
  manifest that is not repairing.
- **`cancel!`, in each ledger state.** A publication cancelled with some
  blobs sent, some queued, some failed awaiting repair, and some
  outstanding is reported by the next step with `:ended
  :dao.space.dht/cancelled`, `:repairing? false`, the sent blobs under
  `:sent` and every other under `:failed` as `:dao.space.dht/cancelled`,
  and the invariant holds. The outstanding write's `/sent` fact then
  arrives: no further event for the cancelled manifest is produced and its
  reported counts are unchanged.
- **Shared addresses.** Two live publications holding one address issue
  one request; its outcome is written to both ledgers. Cancelling the
  first leaves the address queued for the second, which is later reported
  with that address `sent`; the late fact of an address outstanding at the
  cancel is counted by the second and not by the first.
- `cancel!` before the first report produces `:published`, after it
  `:republished`; it is refused for a manifest with no live publication.
- `close!` reports nothing, discards both queues, and leaves every blob
  local; a node reopened on the directory issues no repair request.
- A solo node and a non-publishing node hold no backlog and report
  `/solo` and `/unpublished` in the step that reads the puts.
- Each of `/exhausted`, `/deadline`, `/solo` and `/busy` reaches the
  `:failed` reason as `:cause`.
- A peer serving bytes that do not hash to the address never produces
  `:loaded`; the blob is not in `:local`.
- Exactly one terminal event per load. `forget` clears a terminal record
  and is refused while loading.
- Time advances only by appended ticks.

**L2 — the closure walker and module load, plain Clojure, by address.**
Files: `src/cljc/yin/vm/linker/publish.cljc`, new `src/cljc/yin/vm/linker/closure.cljc`, new
`src/cljc/yin/vm/linker/dht.cljc`, new
`test/yin/vm/linker/closure_test.cljc`, new
`test/yin/vm/linker/dht_test.cljc` (over `test/dao/jing/dht/mesh.cljc`).
Acceptance:
- `publish-module!` walks the closure it minted and refuses unless the walk is `:complete`; a `:requires` address that holds no valid manifest is refused by that walk.
- With the closure otherwise complete, removing in turn the manifest, a
  leaf row, an interior row, each of the three derivation records, each of
  the three lowered images, and a blob of a transitively required module
  yields `:missing` naming exactly that address and role.
- Each closed code of 4.2 is produced by a test: a blob that does not hash
  to its address, a corrupt manifest, a defective row, a defective record,
  a record leading from another tree, an H with no index entry, an image
  that fails its identity, and a bound exceeded. Each yields `:invalid`
  with that code and the load fails without retry.
- A complete walk's `:requires` lists every requirement of every module in
  the closure with its pinned address.
- `dependency-bindings` over two principals and two snapshots, P1
  publishing `base` and P2 publishing `app` pinned to it:
  **matching** (both snapshots loaded, both declared) is `:ok` and `app`
  links and runs; **missing** is `:absent` both when P1's snapshot is not
  loaded and when P1 is not declared, the second carrying the
  `:undeclared-principal` diagnostic; **conflicting** is `:mismatch` when
  P1 has republished `base` at another address in a loaded snapshot, and
  `:ambiguous` when P2 also asserts `base` at another address; P1 and P2
  both asserting the pinned address is `:ok` with both asserters. A direct
  entry for `base` at the pinned address is `:ok` with no signature at all.
- Two mesh nodes: A publishes and announces, acknowledged; B loads by
  address; then **links on all four formats while the node's request ring
  records zero `:jing/get`**, and each result is B0-equal to A's local run.
- Linking one format never makes a load `:loaded`: the load is `:loaded`
  only on the whole closure.
- A missing image no peer holds ends `:failed` with the miss cause; A with
  `publish?` false ends B's load `/exhausted`.
- `link` before `:loaded` is `:yin.link.dht/not-loaded`.

**L3 — the REPL's DHT link source and the relevant re-check.** Files:
`src/cljc/yin/repl/link.cljc`, `src/cljc/yin/repl.cljc`,
`src/cljc/yin/repl/main.cljc`, `src/cljc/yin/repl/dht.cljc`,
`src/cljc/yin/repl/query.cljc`, `test/yin/repl/dht_test.cljc`,
`test/yin/repl/require_test.cljc`. Names are direct addresses in this
slice. Acceptance:
- `yin.repl.link` builds its DHT-source link runtime with `yin.vm.linker/local-runtime`. The attempt budget of the `:content-store` and `:content-client` sources is unchanged.
- A require whose module a peer holds parks, and completes on a later tick
  **with no typed line**; the export then answers.
- An unrelated node event (a publication settling, an index load, another
  module's load) causes no re-check and leaves `:checks` unchanged.
- A repeated or late event for an answered link changes nothing.
- A function policy that abandons at N checks counts only re-checks of this
  run; `:keep` never ends it.
- `(abandon)` during a load ends the require; the load's later completion
  settles no later require; a new require of the same name finds the
  closure `:loaded` and links.
- A failed load raises the section 9 refusal, and a new require starts a
  new load. Each of the three load failures raises its one response shape:
  `:absent` with `:cause`, `:descriptor-defect` with `:code`, and
  `:yin.link.dht/unaskable` with `:outcome`; the raised data is asserted
  equal on the JVM, Node and Dart.
- A dependency binding that is not `:ok` raises
  `:yin.link.dht/dependency-binding` with the 7.4 entry, and no install
  child starts.
- A solo node's require fails with cause `/solo` after the node's next
  step.
- All four VMs: the closed corpus links and answers; a failed load raises
  the same refusal on each; the register fallback policy behaves as
  composed.
- `(dao.space.dht/load-module m)` and `module-status` answer through the
  plain functions.
- The banner and `(help)` state that a require may fetch from peers.

**L4 — rows every round, names in the index, publish at the prompt.**
Files: `src/cljc/yin/repl/index.cljc`,
`src/cljc/yin/repl/query.cljc` (the `yin.link` host
module), `src/cljc/yin/repl/link.cljc`, `src/cljc/yin/repl.cljc`,
`src/cljc/yin/repl/main.cljc` (flags, banner, keygen),
`src/cljc/yin/vm/linker/publish.cljc` (`module-from-index`),
`src/cljc/yin/vm/linker/dht.cljc` (`snapshots`, `names`), the tests beside
each, and the design docs this one cross-references. Acceptance:
- After a round, every row of the evaluated program is in the index store,
  and a second node loads that program's tree by its `:yin.repl/root`
  address.
- A program of more rows than the DHT's pending-write bound is published
  `:acknowledged`, never `/busy`, and so are eight such rounds in a row.
- **A failed row replication.** With one row's store refused by every
  peer, the round is indexed, HEAD moves, and the publication is `:partial`
  naming that row. A second node loads the index, and its load of that
  program's tree fails `:miss` on exactly that address when its lookup does
  not reach the publisher, and succeeds when it does. Once the peers heal,
  the publisher's node repairs with no call from anyone: after
  `:repair-ticks` the REPL prints the `:republished :acknowledged` line and
  a new load succeeds. The first line names the result, the failed count
  and that the node is retrying. `(dao.space.dht/retry m)` brings the same
  repair forward.
- A round whose manifest blob is refused is `:unacknowledged`; after the
  peers heal it is repaired to `:acknowledged` the same way.
- With peers that never accept, twenty rounds at the prompt print twenty
  first reports and no acknowledgement, the prompt never stalls, and the
  shell's ticker returns to its idle cadence between repair cycles.
- A module published at the prompt in a `:partial` round: a reader
  resolves its name, and its require is refused `:absent` with the miss
  cause, as data, until repair completes; a require after it evaluates.
- A two-principal dependency at the prompt: `app` requiring `base` from
  another publisher evaluates when the reader has loaded both indexes and
  declared both principals, and raises `:yin.link.dht/dependency-binding`
  with `:absent`, `:mismatch` and `:ambiguous` in the three cases of L2.
- `(yin.link/publish 'my.lib '[f])` over two defining programs, one free
  primitive and one linker-required module produces one tree in `t` order
  and a manifest declaring both; an undefined export, an undeclared free
  name, a host-module name and a missing key each refuse and write nothing.
- Republishing a name with a new manifest writes the retraction and the
  assertion; a reader of the new snapshot resolves the new address; a
  reader holding only the old snapshot resolves the old one.
- The fold over the snapshot set: an undeclared principal and a bad
  signature are reported and do not resolve; two declared principals on
  different addresses refuse `:ambiguous-name` naming both; two on the same
  address resolve with both in provenance; a retraction removes exactly its
  assertion; an envelope present in two snapshots counts once.
- Only HEAD and loaded index manifests are folded: an envelope blob that is
  merely in the local store is never considered.
- The key file cases of 6.5: absent, malformed, mismatched public key,
  keygen refusing to overwrite. The seed appears in no output.
- The next sequence is derived from the index after a restart.

**L5 — end to end.** Files: `test/yin/repl/dht_process_test.clj`, new
`test/yin/vm/linker/dht_end_to_end_test.cljc`,
`docs/agents/build-n-test.md`. Acceptance:
- Process A (`dht:<dir>`, a peer, `--dht-publish`, `--dht-key`) defines a
  function and publishes it as a module. The test reads A's index manifest
  address and principal from A's output.
- Process B (`--dht-manifest`, `--dht-principal`) requires the module by
  name, waits through pending, and evaluates the export, on each of the
  four VMs. B is run on the JVM and on Node.
- Plain Clojure does the same through
  `join → load-index → names → load-module → link`, the very functions the
  host module calls, and runs the image; it also asserts each failure
  result of section 9 as data.
- Without `--dht-principal`, B's require is `:absent` with an
  `:undeclared-principal` diagnostic.
- The in-process form of the same scenario passes on Dart over the mesh
  seam, signatures verified, on all four VMs.
- The store corpus (an export reading a module-level definition) evaluates on the semantic, stack and register VMs and refuses on the walker with the linker's reason.

## 13. Deferrals

- **The persistent stepped linker.** `link-manifest` as a state machine the
  ticker advances beside the node, with the node's rings as the content
  pair and no prefetch. The staged load is the interim implementation, and
  its acceptance gate is L2's zero-fetch link.
- **Latest-root discovery and rendezvous.** A reader is handed every index
  manifest address it loads.
- **The `:lease` link policy.** `yin.repl.link-policy.md` section 6.
- **Key rotation and revocation.** A rotation or revocation envelope under
  the same fold is the candidate (`yin.vm.linker.md` section 12).
- **Resolving a transitive require by its pinned address**, and delivery of
  a dependency closure as one response (`yin.vm.linker.md` 7.4).
- **Relaying a foreign assertion.** A signed envelope is carrier
  independent, so a publisher could copy a dependency's envelope and proof
  into its own index and spare the reader one index load. The reader would
  still have to declare the foreign principal. Not built; section 7.4
  forbids it to this epic's publisher.
- **Repair across restarts.** The publication ledger is process state. A
  publication not acknowledged when its node closes is not repaired by the
  next process.
- **FFI operations and parked ids in a published module.** Schema 1 cannot
  declare them; the publisher refuses.
- **Step 5a for module-level reads in the tree format.** Which modules link on the walker is the linker's rule, not this epic's.
- The three hosts' verifiers may disagree on adversarial encodings that only the key holder can craft (small-order keys, non-canonical points).
- **Host modules as dependencies of a published module.**
- **Availability repair.** Pinning, re-replication of content whose holders
  left, and garbage collection are `dao.jing.dht.md`'s deferrals.
- **A node id derived from the publisher's key** (`dao.jing.dht.md` 6).
- **ShiBi.** Declarations and write capabilities as tuples supplied to the
  composition.
- **Signed names in a browser build.**

## Lineage

The split is Datomic's and Unison's at once: facts about code are datoms a
query reads, and code to run is content a hash names. The name layer is the
petname idea made fail-closed: a key is its own name, a reader chooses whose
claims count, and two claims that disagree resolve nothing.
