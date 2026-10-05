# yin.vm.linker over dao.jing.dht: publish, load and evaluate code by name

Status: **contract frozen 2026-10-01 (linker-over-DHT epic slice L-design,
revision r5); slices L0 to L5 are implemented and landed on master
(df7cf1f4).** Revision r5 closes the one finding
of the r4 sign-off
(<code>collab/1790808100000-architect-linker-over-dht-design-doc-signoff<!--
-->-r4.gpt-6-sol.findings.md</code>):
`:republished` reports any result change after the first report, whatever
wrote the ledger (5.5.3). Revision r4 closed the two
findings of the r3 sign-off
(<code>collab/1790808100000-architect-linker-over-dht-design-doc-signoff<!--
-->-r3.gpt-6-sol.findings.md</code>):
the repair queue has its own capacity and its own admission turn, and
publications awaiting a first report are bounded (5.5.2, 5.5.4). Revision
r3 closed the two
lifecycle findings of the r2 sign-off
(<code>collab/1790808100000-architect-linker-over-dht-design-doc-signoff<!--
-->-r2.gpt-6-sol.findings.md</code>)
and adopts owner decision 5, automatic retry while open: section 5.5 is
rewritten around one complete publication ledger. Revision r2 closed the five
findings
of the withheld sign-off
(<code>collab/1790808100000-architect-linker-over-dht-design-doc-signoff<!--
-->.gpt-6-sol.findings.md</code>):
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
(<code>collab/1790806000000-architect-linker-over-dht-second-opinion.gpt<!--
-->-6-sol.findings.md</code>,
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

**Out of scope:** rendezvous, key rotation and revocation, the persistent
stepped linker, the `:lease` link policy, authenticated node ids. Section 13
lists every deferral. Latest-root discovery is no longer deferred: a reader
follows a declared publisher's HEAD as a signed trace
([`yin.vm.linker.dht.head.md`](./yin.vm.linker.dht.head.md)), and the head
it installs joins the snapshot set (7.2).

## 2. The starting tree (master `c66809fa`)

<table>
<tr>
<th>
Fact
</th>
<th>
Where
</th>
</tr>
<tr>
<td>
A link attempt builds a fresh link state and fresh cursors, runs
<code>link-manifest</code> synchronously for at most 64 drive rounds, and
reports <code>:pending</code>. The <code>:remote</code> drive advances only a
counter.
</td>
<td>
<code>src/cljc/yin/repl/link.cljc:167-227</code>
</td>
</tr>
<tr>
<td>
A <code>dao.space.dht</code> node's byte-store handle reads <code>:local</code>
only. <code>load-index</code> stages a miss around the handle: walk against a
probing handle, fetch the missing blob with <code>:jing/get</code>, walk again.
</td>
<td>
<code>src/cljc/dao/space/dht.cljc:457-597</code>; <code>dao.jing.dht.md</code>
5.1
</td>
</tr>
<tr>
<td>
The REPL's index holds AST datoms with <code>:yin/address</code> facts naming
row addresses. The rows themselves are never stored:
<code>yin.vm/materialize-tree!</code> has no caller in <code>src/</code>.
</td>
<td>
<code>src/cljc/yin/repl/index.cljc</code>;
<code>src/cljc/yin/vm.cljc:1472</code>
</td>
</tr>
<tr>
<td>
A REPL definition is the application <code>(yin/def 'f value)</code>: operator
the variable <code>yin/def</code>, first operand a literal symbol. Definitions
are found by query; no definition-name fact exists.
</td>
<td>
<code>yin/vm.cljc:296</code>; <code>test/dao/space/query_test.cljc</code>
</td>
</tr>
<tr>
<td>
<code>yin.vm.linker/publish!</code> stores one format image. Module manifests
are assembled only by test helpers.
</td>
<td>
<code>linker.cljc:1992</code>; <code>test/yin/repl/require_test.cljc:134</code>
</td>
</tr>
<tr>
<td>
The authority fold (<code>yin.vm.linker.md</code> 8.2) is implemented and wired
into nothing. The REPL's name environment is a plain map.
</td>
<td>
<code>src/cljc/yin/vm/linker/authority.cljc</code>;
<code>yin/repl.cljc:862</code>
</td>
</tr>
<tr>
<td>
The fold resolves a name when every accepted assertion names the same address,
keeping all asserters; it refuses only on more than one distinct address.
</td>
<td>
<code>authority.cljc:226-255</code>
</td>
</tr>
<tr>
<td>
<code>yin.repl/recheck-pending</code> exists; no host driver calls it. A parked
require progresses only on a typed line.
</td>
<td>
<code>yin/repl.cljc:1848</code>; <code>yin/repl/main.cljc:286</code>
</td>
</tr>
<tr>
<td>
The <code>dao.space.dht</code> host module answers <code>load-index</code>,
<code>load-status</code> and <code>q</code> as effects on the query call pair,
threading the node through <code>query/serve</code>.
</td>
<td>
<code>src/cljc/yin/repl/query.cljc:127-153, 570-616</code>
</td>
</tr>
<tr>
<td>
A module whose export reads another module-level definition from inside a lambda
body links on the semantic, stack and register backends. The tree scanner alone
retains the read (its path order places the operator's body before the operand
that defines the name), and the manifest cannot declare it.
</td>
<td>
<code>require_test.cljc:90-98</code>; <code>linker_test.cljc</code>
<code>a-definition-dominating-every-application-discharges-a-body-occurr<!--
-->ence</code>; <code>yin.vm.linker.md</code> 4.2 step 5a
</td>
</tr>
<tr>
<td>
<code>dao.jing/canonical-bytes</code> is deterministic CBOR, identical across
hosts.
</td>
<td>
<code>src/cljc/dao/jing.cljc:279</code>
</td>
</tr>
<tr>
<td>
A DHT node holds at most 64 pending writes; a write beyond that is
<code>/busy</code>.
</td>
<td>
<code>dao.jing.dht.md</code> 9
</td>
</tr>
<tr>
<td>
The node's handle appends one replicate request per put, at once, and a
publication with any refused blob is reported <code>:acknowledged? false</code>
with one reason.
</td>
<td>
<code>src/cljc/dao/space/dht.cljc:223-227, 421-429</code>
</td>
</tr>
<tr>
<td>
<code>yin.vm/ast-requirements</code> answers a tree's store keys (definitions
included) and its effects, normalized by <code>footprint-table</code>.
<code>yin.vm/profile-of</code> answers a primitive's profile with its
<code>:yin.k/effects</code>.
</td>
<td>
<code>src/cljc/yin/vm.cljc:415, 1682, 1818</code>
</td>
</tr>
</table>

## 3. Rulings

<table>
<tr>
<th>
#
</th>
<th>
Ruling
</th>
<th>
Source
</th>
</tr>
<tr>
<td>
R1
</td>
<td>
The linker's content source is the <code>dao.space.dht</code> node's own store
handle, the <code>:kind :local</code> row of <code>yin.vm.linker.md</code> 6.1.
A module's closure is loaded onto the node first, by an explicit closure walker
(section 4). The node stays the only step owner; no linker drive steps it.
</td>
<td>
lead; second opinion
</td>
</tr>
<tr>
<td>
R2
</td>
<td>
Two things are published: the code index (facts) and the module closure
(content). The name binding is one signed envelope in the index. No datom family
is added for images.
</td>
<td>
lead; second opinion
</td>
</tr>
<tr>
<td>
R3
</td>
<td>
Every evaluated program's rows are materialized into the index store every
round. An index <code>:yin/address</code> fact names retrievable content.
</td>
<td>
owner decision 1
</td>
</tr>
<tr>
<td>
R4
</td>
<td>
A name is resolved from signed envelopes in explicitly loaded index snapshots,
under the fold of <code>yin.vm.linker.md</code> 8.2. No name-environment root is
published. Direct manifest addresses stay admissible without any signature.
</td>
<td>
lead; second opinion
</td>
</tr>
<tr>
<td>
R5
</td>
<td>
The proof kind for a DHT-discovered name is a signature. An attested-log proof
does not survive this transport.
</td>
<td>
lead; second opinion
</td>
</tr>
<tr>
<td>
R6
</td>
<td>
Signatures are Ed25519 on the JVM, Node and Dart, with cross-host vectors.
Canonical signed bytes, key encoding and the principal id are fixed in section 6
before any adapter is written.
</td>
<td>
owner decision 3; second opinion
</td>
</tr>
<tr>
<td>
R7
</td>
<td>
A publisher's key is stable and loaded from a file. Loss and replacement are
defined in section 6.5. Tests may use ephemeral keys.
</td>
<td>
owner decision 2
</td>
</tr>
<tr>
<td>
R8
</td>
<td>
The same name bound to the same address by several declared principals resolves,
with every asserter in provenance. Different addresses refuse
<code>:ambiguous-name</code>.
</td>
<td>
owner decision 4
</td>
</tr>
<tr>
<td>
R9
</td>
<td>
A module is published explicitly, by name and export list, with its tree derived
from indexed code.
</td>
<td>
adopted recommendation
</td>
</tr>
<tr>
<td>
R10
</td>
<td>
A <code>require</code> under a DHT link source may start a bounded load. The
banner and the help say so. Pending and failure are data.
</td>
<td>
adopted recommendation
</td>
</tr>
<tr>
<td>
R11
</td>
<td>
The host ticker re-checks a pending run only when a load that run waits on
completes or fails.
</td>
<td>
second opinion
</td>
</tr>
<tr>
<td>
R12
</td>
<td>
A DHT request budget is not a lease (section 8.3).
</td>
<td>
second opinion
</td>
</tr>
<tr>
<td>
R13
</td>
<td>
A publication that is not acknowledged is retried automatically, in the
background, while the node is open and within the backlog bound. Retry updates
the original publication's ledger (section 5.5).
</td>
<td>
owner decision 5
</td>
</tr>
</table>

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
(walk handle manifest-address {:bounds {:max-parts n :max-depth d :max-bytes
b}})

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

<table>
<tr>
<th>
<code>:code</code>
</th>
<th>
Raised by
</th>
</tr>
<tr>
<td>
<code>:address-mismatch</code>
</td>
<td>
A blob that fails <code>segment-matches?</code>.
</td>
</tr>
<tr>
<td>
<code>:manifest-defect</code>
</td>
<td>
<code>manifest-defect</code>.
</td>
</tr>
<tr>
<td>
<code>:row-defect</code>
</td>
<td>
<code>row-local-defect</code>.
</td>
</tr>
<tr>
<td>
<code>:record-defect</code>
</td>
<td>
<code>record-defect</code>.
</td>
</tr>
<tr>
<td>
<code>:derivation-mismatch</code>
</td>
<td>
A record whose <code>:yin.ledger/input</code> is not the tree.
</td>
</tr>
<tr>
<td>
<code>:index-entry-missing</code>
</td>
<td>
An H or R with no <code>:yin.module/index</code> entry.
</td>
</tr>
<tr>
<td>
<code>:identity-mismatch</code>
</td>
<td>
An image that fails its format's <code>:identity-matches-fn</code>.
</td>
</tr>
<tr>
<td>
<code>:parts-limit</code>
</td>
<td>
A walk bound exceeded.
</td>
</tr>
</table>

`role` is one of `:manifest`, `:row`, `:record`, `:image`, `:require`.
`path` is the chain of manifest addresses from the root to the module the
blob belongs to.

The closure of one manifest is, in walk order:

<table>
<tr>
<th>
Step
</th>
<th>
Blob
</th>
<th>
Check before continuing
</th>
</tr>
<tr>
<td>
1
</td>
<td>
The manifest
</td>
<td>
<code>segment-matches?</code>; <code>manifest-defect</code> is nil.
</td>
</tr>
<tr>
<td>
2
</td>
<td>
Every row of <code>:yin.module/tree</code>
</td>
<td>
A bounded worklist over the grammar's child slots (<code>ast-format</code>'s
<code>:parts-fn</code>); each row <code>segment-matches?</code> and has no
<code>row-local-defect</code>.
</td>
</tr>
<tr>
<td>
3
</td>
<td>
The derivation record of each of <code>:yin.semantic/code</code>,
<code>:yin.debruijn.code</code>, <code>:yin.debruijn.register</code> the
manifest names
</td>
<td>
<code>segment-matches?</code>; <code>record-defect</code> is nil;
<code>:yin.ledger/input</code> equals the tree.
</td>
</tr>
<tr>
<td>
4
</td>
<td>
The image each record's <code>:yin.ledger/output</code> identifies
</td>
<td>
Its storage address is the identity itself for the semantic vector, and the
<code>:yin.module/index</code> entry for H and R; an identity with no index
entry is <code>:invalid</code>. The image satisfies its format record's
<code>:identity-matches-fn</code>.
</td>
</tr>
<tr>
<td>
5
</td>
<td>
The closure of every manifest under <code>:yin.module/requires</code>
</td>
<td>
Recursively, depth bounded; a manifest already visited is not walked again.
</td>
</tr>
</table>

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
- An address is loaded under one kind. A load of the same kind already
  `:loading`, `:loaded` or `:failed` is left as it is; a load under another
  kind is refused `:dao.space.dht/kind-conflict` (section 9) and the record
  is left as it is. `forget` removes a terminal record; it is refused for a
  `:loading` one.
- `(dao.space.dht/abandon node address)` removes a `:loading` record and
  emits no event; it is refused `:dao.space.dht/not-loading` for any other.
  It retires the record's interest in the client: the retained unsent
  request, when it is this record's, by `dao.jing.content.step/abandon`;
  otherwise its outstanding request by `dao.jing.content.step/retire`, so
  a late answer is unsolicited and dropped. Another load fetching the same
  address has its own request and is untouched. The node steps its client
  while a record is loading or while the client holds an unsent request, an
  outstanding id or an undelivered completion, so what an abandon leaves
  drains with no load active.

**Load status:**

```clojure
{:status :loading :kind k :fetched n :fetching address-or-nil}
{:status :loaded  :kind k :fetched n :value v}
{:status :failed  :kind k :fetched n :reason reason}
```

**Failure reasons are data.** S5's strings are replaced, with no
compatibility path:

<table>
<tr>
<th>
<code>:reason</code>
</th>
<th>
Meaning
</th>
</tr>
<tr>
<td>
<code>{:dao.space.dht/failure :miss :address a :cause c}</code>
</td>
<td>
No peer produced <code>a</code>. <code>c</code> is the <code>/miss</code> fact's
reason for that address (<code>/exhausted</code>, <code>/deadline</code>,
<code>/solo</code>, <code>/busy</code>), or <code>:dao.jing.dht/gap</code> when
the fact was lost.
</td>
</tr>
<tr>
<td>
<code>{:dao.space.dht/failure :invalid :address a-or-nil :defect {:code <!--
-->c ...}}</code>
</td>
<td>
The walk refused local content, threw, or answered another shape.
</td>
</tr>
<tr>
<td>
<code>{:dao.space.dht/failure :unaskable :address a :outcome o}</code>
</td>
<td>
The request for <code>a</code> could not be submitted; <code>o</code> is the
client's outcome keyword.
</td>
</tr>
</table>

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
 :identities {:yin.semantic/code v :yin.debruijn.code H :yin.debruijn.register
 R}
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

<table>
<tr>
<th>
Input
</th>
<th>
What is read
</th>
</tr>
<tr>
<td>
The module tree
</td>
<td>
<code>(yin.vm/ast-requirements db)</code> over the <code>$ast</code> relation of
the tree's rows,
<code>(vals (:rows (yin.vm/ast-&gt;semantic-bytecode ast)))</code>: its
<code>:store-keys</code>, <code>:effects</code>, <code>:ffi-ops</code> and
<code>:parked-ids</code>.
</td>
</tr>
<tr>
<td>
<code>:primitives</code>
</td>
<td>
Each declared profile's <code>:yin.k/effects</code>.
</td>
</tr>
<tr>
<td>
<code>:requires</code>
</td>
<td>
Each required manifest, read from <code>handle</code>: its own
<code>:yin.module/footprint</code> <code>:effects</code>.
</td>
</tr>
</table>

```clojure
{:store-keys <the tree's :store-keys>
 :effects    <the tree's :effects
              union every declared primitive's :yin.k/effects
              union every required manifest's footprint :effects>}
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
- **Closure by linked requirements.** For each linked module declared as a
  requirement by a free qualified name in a collected program, also collect the
  latest indexed program, by `t`, whose module-level form requires that module.
  Repeat definition and requirement collection to a fixed point. Sequence every
  collected program once in ascending `t`. If no such requiring program exists,
  refuse publication with `:yin.link.publish/missing-require-program`, naming
  the module. The manifest still pins the module address derived from the
  session's linked module registry; the index does not declare reader
  principals.
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

**What links where.** Step 5a of `yin.vm.linker.md` is unchanged by this epic. A
module whose exported lambda reads another module-level definition is published
with `:links` showing the tree format refused (`:undeclared-free`, naming the
read) and the semantic, stack and register formats ok, exactly as the starting
tree behaves. Such a module loads and evaluates on the semantic, stack and
register VMs. `:links` is the linker's own verdict per format; the publisher
never restates it. The publisher prints which.

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

<table>
<tr>
<th>
State
</th>
<th>
Meaning
</th>
</tr>
<tr>
<td>
<code>waiting</code>
</td>
<td>
No outcome yet: the address is queued or its request is outstanding, for the
first time in this publication.
</td>
</tr>
<tr>
<td>
<code>sent</code>
</td>
<td>
A <code>/sent</code> fact arrived while this publication held the address. Never
leaves this state.
</td>
</tr>
<tr>
<td>
<code>failed</code>
</td>
<td>
The last outcome was not <code>/sent</code>. Carries that reason. May become
<code>sent</code> by a later repair.
</td>
</tr>
</table>

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
  capacity, `:max-repairing x :repair-batch` (16 x 64 = 1024 by default),
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
  fresh and `:max-repairing x :repair-batch` repair queue entries; at most
  `max-pending-writes` requests outstanding. `:max-repairing` bounds
  repairing publications only.
- **Release.** Each step appends replicate requests while the number
  outstanding is below the DHT's `max-pending-writes`. The node never has
  more outstanding than that bound, so its own writes never draw `/busy`.
- **Fairness between the queues.** While the repair queue is non-empty,
  `:repair-slots` (default 16) of the outstanding bound are reserved for
  it: fresh requests hold at most `max-pending-writes - repair-slots`
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

<table>
<tr>
<th>
<code>:result</code>
</th>
<th>
Condition, over the whole ledger
</th>
<th>
What it promises
</th>
</tr>
<tr>
<td>
<code>:acknowledged</code>
</td>
<td>
Every blob sent.
</td>
<td>
Every address of the publication was handed to at least <code>ack-peers</code>
peers.
</td>
</tr>
<tr>
<td>
<code>:partial</code>
</td>
<td>
The manifest blob sent; at least one other blob not.
</td>
<td>
Peers can find the publication. The addresses under <code>:failed</code> are
held and served by the publisher only.
</td>
</tr>
<tr>
<td>
<code>:unacknowledged</code>
</td>
<td>
The manifest blob not sent.
</td>
<td>
Nothing beyond the local copy. <code>:failed</code> still lists every blob not
sent.
</td>
</tr>
</table>

- The result is always computed over the **whole ledger**. A blob sent in
  the first pass and a blob sent by a repair count alike. A publication
  whose manifest was sent in the first pass and whose rows were sent by
  repair is `:acknowledged`.
- `:failed` is complete, in address order.
- `:acknowledged?` is removed, with no compatibility path.

**Which event, when.** A publication produces:

<table>
<tr>
<th>
Event
</th>
<th>
When
</th>
<th>
Times
</th>
</tr>
<tr>
<td>
<code>:published</code>
</td>
<td>
The <strong>first report</strong>: every blob has left <code>waiting</code>.
</td>
<td>
Exactly once.
</td>
</tr>
<tr>
<td>
<code>:republished</code>
</td>
<td>
After the first report, the <code>:result</code> changed (to
<code>:partial</code> or to <code>:acknowledged</code>), <strong>whatever wrote
the ledger</strong>, or repair ended. A change that ends repair is one event.
</td>
<td>
Zero or more; at most two result changes and one end.
</td>
</tr>
</table>

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

<table>
<tr>
<th>
Entry at the call
</th>
<th>
Becomes
</th>
<th>
The write itself
</th>
</tr>
<tr>
<td>
<code>sent</code>
</td>
<td>
Stays <code>sent</code>.
</td>
<td>
Done.
</td>
</tr>
<tr>
<td>
<code>failed</code>, nothing outstanding
</td>
<td>
<code>failed</code> with reason <code>:dao.space.dht/cancelled</code>; the
earlier reason is kept under <code>:was</code>.
</td>
<td>
Its queued repair entry, if any, is removed, unless another live publication
holds the address.
</td>
</tr>
<tr>
<td>
<code>waiting</code>, queued
</td>
<td>
<code>failed</code> with reason <code>:dao.space.dht/cancelled</code>.
</td>
<td>
Its queue entry is removed, unless another live publication holds the address.
</td>
</tr>
<tr>
<td>
<code>waiting</code> or <code>failed</code>, <strong>request
outstanding</strong>
</td>
<td>
<code>failed</code> with reason <code>:dao.space.dht/cancelled</code>.
</td>
<td>
The request cannot be withdrawn. It runs to its fact.
</td>
</tr>
</table>

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

<table>
<tr>
<th>
Function
</th>
<th>
Meaning
</th>
</tr>
<tr>
<td>
<code>(generate)</code>
</td>
<td>
A new key <code>{:seed hex :public hex}</code> from the host CSPRNG.
</td>
</tr>
<tr>
<td>
<code>(public-of seed)</code>
</td>
<td>
The public key of a seed.
</td>
</tr>
<tr>
<td>
<code>(principal public)</code>
</td>
<td>
<code>&quot;ed25519:&quot; + public</code>.
</td>
</tr>
<tr>
<td>
<code>(sign-envelope seed envelope)</code>
</td>
<td>
The proof map of 6.3.
</td>
</tr>
<tr>
<td>
<code>(verify-envelope public canonical-bytes signature)</code>
</td>
<td>
True or false; never throws.
</td>
</tr>
<tr>
<td>
<code>(key-text key)</code>, <code>(key-from-text s)</code>
</td>
<td>
The key file's content, both ways; <code>key-from-text</code> refuses with a
reason.
</td>
</tr>
</table>

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
  file on such a host -- `chmod 600` it at once, or create it under
  `umask 077` or in a directory only its owner can read -- and on any
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

<table>
<tr>
<th>
Event
</th>
<th>
Consequence
</th>
<th>
Remedy
</th>
</tr>
<tr>
<td>
The key file is lost.
</td>
<td>
That principal can never sign again. Its assertions stand wherever a reader
still declares it, and can never be retracted.
</td>
<td>
Generate a new key: a new principal. Publish again under it. Every reader
replaces the old declaration with the new one.
</td>
</tr>
<tr>
<td>
A reader declares both the old and the new principal.
</td>
<td>
A name both bind to the same manifest resolves (R8). A name they bind to
different manifests is <code>:ambiguous-name</code>.
</td>
<td>
The reader removes the old declaration.
</td>
</tr>
<tr>
<td>
The key is kept and the index directory is lost.
</td>
<td>
The derived sequence restarts at 1. Two distinct envelopes then share a
sequence; a reader folding an old and a new snapshot sees equivocation and
discards that principal from that sequence on.
</td>
<td>
Hydrate the directory from the last published index manifest before publishing,
or use a new key.
</td>
</tr>
<tr>
<td>
The key is disclosed.
</td>
<td>
Anyone holding it can bind names for that principal. Nothing in this epic
revokes a key.
</td>
<td>
Every reader removes the declaration; the publisher takes a new key.
</td>
</tr>
</table>

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
there is one; every manifest whose `:dao.space.dht/index` load is
`:loaded` on that node; and the installed head of each principal the node
follows (`yin.vm.linker.dht.head.md` 5.5), read from the local store,
where every blob of it already is. Nothing else in the local store is
considered: a candidate head, loading or loaded, is in no snapshot set.

- Loading an index is not a subscription. A publisher's later HEAD reaches a
  reader only when its address is handed over and loaded, or when the
  reader follows the publisher's principal and installs the head.
- Following never writes the reader's own HEAD. Installation replaces a
  principal's installed head in one step, so no fold sees two heads of one
  principal, or none.
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
  - Scope: a dangling retraction whose target is not an assertion in the set.
    Either the target is in no snapshot, or it is another retraction.
  - Why it cannot be per-name: the envelope carries only the target's id, and
    only an assertion carries a name, so the reader cannot tell which name it
    would retract.
  - What the reader gets: the diagnostic is under `:global-diagnostics`, once.
    It carries `:principal`, the retraction's principal, and `:of`, the
    assertion id it names. It is in no per-name diagnostic. `resolve-name` and
    `dependency-bindings` never carry it, and `(yin.link/names)` returns it as
    part of this answer.
  - Retraction inside the set: when the referenced assertion is in the set,
    for example another principal's assertion that this principal cannot
    retract, the diagnostic names that assertion's name and stays under
    `:diagnostics`.
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

<table>
<tr>
<th>
Outcome
</th>
<th>
Carried data
</th>
<th>
When
</th>
</tr>
<tr>
<td>
<code>:absent</code> (name)
</td>
<td>
<code>:name</code>, <code>:diagnostics</code> for that name
</td>
<td>
The fold found no accepted assertion. Diagnostics name every discarded envelope
for the name: <code>:unauthenticated</code> (<code>:no-proof</code>,
<code>:bad-proof</code>), <code>:undeclared-principal</code>,
<code>:replay</code>, <code>:equivocation</code>,
<code>:dangling-retraction</code> (only when the retracted assertion is in the
snapshot set), <code>:malformed-envelope</code>. A retraction of an assertion
outside the set names no name. It is never here; it is reported once under the
fold's <code>:global-diagnostics</code> (7.3).
</td>
</tr>
<tr>
<td>
<code>:ambiguous-name</code>
</td>
<td>
<code>:addresses</code>, <code>:asserters</code>
</td>
<td>
More than one distinct address.
</td>
</tr>
<tr>
<td>
<code>:absent</code> (content)
</td>
<td>
<code>:address</code>, <code>:cause</code> the <code>:miss</code> cause
</td>
<td>
The load failed <code>:miss</code>.
</td>
</tr>
<tr>
<td>
<code>:descriptor-defect</code>
</td>
<td>
<code>:address</code> (or nil), <code>:code</code> the defect's closed code,
<code>:detail</code> and <code>:text</code> when present
</td>
<td>
The load failed <code>:invalid</code>: the closure walk's codes of 4.2,
<code>:dao.space.dht/walk-threw</code>, or
<code>:dao.space.dht/walk-shape</code>.
</td>
</tr>
<tr>
<td>
<code>:yin.link.dht/unaskable</code>
</td>
<td>
<code>:address</code>, <code>:outcome</code> the client's outcome keyword
</td>
<td>
The load failed <code>:unaskable</code>.
</td>
</tr>
<tr>
<td>
<code>:yin.link.dht/dependency-binding</code>
</td>
<td>
the section 7.4 entry: <code>:module</code>, <code>:name</code>,
<code>:pinned</code>, <code>:binding</code>, and its data
</td>
<td>
A dependency name does not resolve to its pinned address.
</td>
</tr>
<tr>
<td>
<code>:yin.link.dht/closure-incomplete</code>
</td>
<td>
<code>:address</code>
</td>
<td>
A link refused <code>:absent</code> after a <code>:loaded</code> record. A
defect, reported.
</td>
</tr>
<tr>
<td>
The linker's own refusals
</td>
<td>
per <code>yin.vm.linker/refusal-reasons</code>
</td>
<td>
<code>:contract-mismatch</code>, <code>:module-name-mismatch</code>,
<code>:derivation-mismatch</code>, <code>:unverified-derivation</code>,
<code>:undeclared-free</code>, <code>:use-before-definition</code>,
<code>:unresolved-free</code>, <code>:shadowed-free</code>,
<code>:unsupported-format</code>, and the rest, unchanged.
</td>
</tr>
<tr>
<td>
<code>:yin.repl/abandoned</code>, <code>:yin.repl/link-policy</code>
</td>
<td>
per the link policy
</td>
<td>
The run was abandoned.
</td>
</tr>
</table>

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

<table>
<tr>
<th>
<code>:reason</code>
</th>
<th>
Carried data
</th>
<th>
When
</th>
</tr>
<tr>
<td>
<code>:yin.link.publish/invalid-requirement</code>
</td>
<td>
<code>:name</code> the requirement's name
</td>
<td>
The walk (4.2) of a pinned manifest under <code>:requires</code> answers
<code>:invalid</code>. Raised before anything is written.
</td>
</tr>
<tr>
<td>
<code>:yin.link.publish/incomplete-requirement</code>
</td>
<td>
<code>:name</code>, <code>:walk</code> the walk's <code>:missing</code> outcome
</td>
<td>
A pinned manifest is present but its closure answers <code>:missing</code>
beyond it. Raised before anything is written. A pinned manifest that is itself
absent is <code>/missing-requirement</code>.
</td>
</tr>
<tr>
<td>
<code>:yin.link.publish/incomplete-closure</code>
</td>
<td>
<code>:address</code> the minted manifest, <code>:walk</code> the walk's outcome
</td>
<td>
The walk of the closure just minted is not <code>:complete</code>. Raised after
the writes and before any link: <code>:links</code> is never reported for it.
</td>
</tr>
</table>

Replication outcomes arrive through the `:published` event of
section 5.5; a blob's `:reason` there is one of the DHT's `/unacknowledged`
reasons, `:dao.space.dht/backlog-full`, `:dao.space.dht/publications-full`
or `:dao.space.dht/cancelled`.

Node-call refusals. `dao.space.dht/retry!`, `cancel!`, `forget`, `load`
(and so `load-index` and `yin.vm.linker.dht/load-module`) and `abandon`
refuse by throwing an `ex-info` whose data carries
`{:dao.space.dht/refused code ...}`, with the code from this closed set:

<table>
<tr>
<th>
Code
</th>
<th>
Raised by
</th>
<th>
When
</th>
</tr>
<tr>
<td>
<code>:dao.space.dht/not-repairing</code>
</td>
<td>
<code>retry!</code>
</td>
<td>
No live publication of the manifest is repairing. Carries
<code>:manifest</code>.
</td>
</tr>
<tr>
<td>
<code>:dao.space.dht/not-live</code>
</td>
<td>
<code>cancel!</code>
</td>
<td>
No live publication of the manifest. Carries <code>:manifest</code>.
</td>
</tr>
<tr>
<td>
<code>:dao.space.dht/loading</code>
</td>
<td>
<code>forget</code>
</td>
<td>
The load record is <code>:loading</code>. Carries <code>:address</code>.
</td>
</tr>
<tr>
<td>
<code>:dao.space.dht/kind-conflict</code>
</td>
<td>
<code>load</code>
</td>
<td>
The address is already recorded under another <code>:kind</code>; the record
is left as it is. Carries <code>:address</code>, <code>:kind</code> the kind
asked for, and <code>:recorded</code> the record's kind.
</td>
</tr>
<tr>
<td>
<code>:dao.space.dht/not-loading</code>
</td>
<td>
<code>abandon</code>
</td>
<td>
The address has no <code>:loading</code> record. Carries <code>:address</code>
and <code>:status</code>, the record's status or nil.
</td>
</tr>
</table>

A consumer matches the code, never the message. The `dao.space.dht` host
module answers each one as the call's error under the same code: `retry`,
`cancel`, `load-index` and `load-module` alike, so a refusal of any code
never escapes the host interpreter.

## 10. The plain Clojure API

The one path, for any Clojure program:

```text
join -> load-index -> names -> load-module -> link        (reader)
join -> publish!  -> assert! -> announce!                 (publisher)
```

<table>
<tr>
<th>
Function
</th>
<th>
Meaning
</th>
</tr>
<tr>
<td>
<code>dao.space.dht/join</code>, <code>step</code>, <code>store</code>,
<code>local</code>, <code>announce!</code>, <code>close!</code>
</td>
<td>
S5, unchanged.
</td>
</tr>
<tr>
<td>
<code>dao.space.dht/load-index</code>, <code>load-status</code>,
<code>db</code>, <code>q</code>
</td>
<td>
S5; statuses and reasons per section 4.3.
</td>
</tr>
<tr>
<td>
<code>dao.space.dht/load</code>, <code>forget</code>, <code>abandon</code>
</td>
<td>
Section 4.3; refusal codes in section 9.
</td>
</tr>
<tr>
<td>
<code>(dao.space.dht/loaded-indexes node)</code>
</td>
<td>
The covered-index manifests whose load is <code>:loaded</code>, sorted by
address text. <code>yin.vm.linker.dht/snapshots</code> reads the node's loads
only through it.
</td>
</tr>
<tr>
<td>
<code>dao.space.dht/retry!</code>, <code>cancel!</code>
</td>
<td>
Section 5.5. Each answers the node; refusal codes in section 9.
</td>
</tr>
<tr>
<td>
<code>(dao.space.dht/backlog node)</code>
</td>
<td>
What the node retains now:
<code>{:fresh n :repair n :outstanding n :outstanding-fresh n :outstandi<!--
-->ng-repair n :live n :open n :repairing n :ledger-entries n}</code>. These are
queue entries, requests outstanding (in total and per queue), live publications,
those awaiting a first report, those repairing, and the ledger entries live
publications hold (section 5.5.2).
</td>
</tr>
<tr>
<td>
<code>(dao.space.dht/publications node)</code>
</td>
<td>
Every live publication, oldest first, each
<code>{:manifest m :blobs n :sent s :result r :reported? b :repairing? b<!--
--> :delay ticks :due reading-or-nil :cycles n :cycle-open? b :repair-qu<!--
-->eued n :entries {address entry}}</code>. An entry is
<code>{:state :waiting}</code>, <code>{:state :sent :peers k}</code> or
<code>{:state :failed :reason r :peers k}</code>, with <code>:was</code> after a
cancellation.
</td>
</tr>
<tr>
<td>
<code>(dao.space.dht/publication node manifest-address)</code>
</td>
<td>
The oldest live publication of that manifest in the shape above, or nil.
</td>
</tr>
<tr>
<td>
<code>(dao.space.dht/ack-peers node)</code>
</td>
<td>
The distinct peers a blob must be handed to before it is <code>sent</code>.
</td>
</tr>
<tr>
<td>
<code>yin.vm.linker.closure/walk</code>
</td>
<td>
Section 4.2.
</td>
</tr>
<tr>
<td>
<code>yin.vm.linker.sign/*</code>
</td>
<td>
Section 6.4.
</td>
</tr>
<tr>
<td>
<code>yin.vm.linker.publish/publish-module!</code>, <code>footprint</code>,
<code>module-from-index</code>, <code>assertion</code>, <code>retraction</code>
</td>
<td>
Sections 5.2, 5.3, 6.1. <code>assertion</code> and <code>retraction</code>
answer <code>{:envelope e :proof p :datoms [...]}</code> for a key and a
sequence.
</td>
</tr>
<tr>
<td>
<code>(yin.vm.linker.dht/publish! node spec)</code>
</td>
<td>
5.4.
</td>
</tr>
<tr>
<td>
<code>(yin.vm.linker.dht/publish-name! node db opts)</code>
</td>
<td>
<code>module-from-index</code> over the publisher's own index <code>db</code>,
<code>publish!</code>, and the envelopes of 6.6 signed with <code>:key</code>:
<code>{:status :ok :address a :links {...} :envelopes [...]}</code>, the
envelopes for the caller to commit as one index transaction before it announces;
refused <code>:yin.link.publish/no-key</code> without a key. Every refusal
writes nothing.
</td>
</tr>
<tr>
<td>
<code>yin.vm.linker.publish/next-seq</code>, <code>name-envelopes</code>
</td>
<td>
6.5 and 6.6: the next sequence derived from the publisher's own envelopes in its
index, and what publishing a name writes (nothing for the standing manifest;
else a retraction of each other standing assertion, then the assertion).
</td>
</tr>
<tr>
<td>
<code>(yin.vm.linker.dht/head node)</code>
</td>
<td>
The manifest the HEAD of the node's directory names, or nil (a node over no
durable directory).
</td>
</tr>
<tr>
<td>
<code>(yin.vm.linker.dht/snapshots node)</code>
</td>
<td>
The snapshot vector of 7.2: HEAD, every <code>:loaded</code> index, and each
installed head, sorted.
</td>
</tr>
<tr>
<td>
<code>(yin.vm.linker.dht/installed-heads node)</code>
</td>
<td>
<code>{principal manifest}</code>: the installed head of each followed
principal, as the follower of <code>yin.vm.linker.dht.head.md</code> records
it on the node value.
</td>
</tr>
<tr>
<td>
<code>(yin.vm.linker.dht/authority {:principals [hex ...] :name-env {...<!--
-->}})</code>
</td>
<td>
The reader's authority of 7.1: each declared public key an Ed25519 signature
principal at <code>:seq-floor</code> 0.
</td>
</tr>
<tr>
<td>
<code>(yin.vm.linker.dht/names node authority)</code>
</td>
<td>
7.3.
</td>
</tr>
<tr>
<td>
<code>(yin.vm.linker.dht/resolve-name node authority name)</code>
</td>
<td>
The name's 7.3 entry when it resolves, else its section 9 refusal:
<code>:absent</code> with <code>:name</code> and that name's
<code>:diagnostics</code>, or <code>:ambiguous-name</code> with
<code>:name</code>, <code>:addresses</code>, <code>:asserters</code>. The DHT
link source refuses with it.
</td>
</tr>
<tr>
<td>
<code>(yin.vm.linker.dht/load-module node manifest-address)</code>
</td>
<td>
Starts the closure load; answers the node.
</td>
</tr>
<tr>
<td>
<code>(yin.vm.linker.dht/module-status node manifest-address)</code>
</td>
<td>
The load status of 4.3.
</td>
</tr>
<tr>
<td>
<code>(yin.vm.linker.dht/load-refusal status)</code>
</td>
<td>
The section 9 row of a failed closure load, given the status
<code>module-status</code> answers. <code>:miss</code> gives
<code>:absent</code> with <code>:cause</code>. <code>:invalid</code> gives
<code>:descriptor-defect</code> with <code>:code</code>, plus
<code>:detail</code> and <code>:text</code> when present.
<code>:unaskable</code> gives <code>:yin.link.dht/unaskable</code> with
<code>:outcome</code>. It answers nil for a load that has not failed. The DHT
link source refuses with this row, so there is one conversion, not two.
</td>
</tr>
<tr>
<td>
<code>(yin.vm.linker.dht/dependency-bindings node authority manifest-add<!--
-->ress)</code>
</td>
<td>
7.4; refused <code>:yin.link.dht/not-loaded</code> unless the load is
<code>:loaded</code>.
</td>
</tr>
<tr>
<td>
<code>(yin.vm.linker.dht/link node manifest-address format opts)</code>
</td>
<td>
<code>link-manifest</code> over the node's local store; refused
<code>:yin.link.dht/not-loaded</code> unless the load is <code>:loaded</code>.
</td>
</tr>
</table>

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

<table>
<tr>
<th>
Datum
</th>
<th>
Default
</th>
<th>
Notes
</th>
</tr>
<tr>
<td>
Closure walk bounds
</td>
<td>
<code>yin.vm.linker/default-bounds</code>
</td>
<td>
Parts, depth and bytes, as the linker's own.
</td>
</tr>
<tr>
<td>
Outstanding fetches per load
</td>
<td>
1
</td>
<td>
As <code>load-index</code> today.
</td>
</tr>
<tr>
<td>
Replicate requests outstanding
</td>
<td>
the DHT's <code>max-pending-writes</code> (64)
</td>
<td>
Section 5.5.
</td>
</tr>
<tr>
<td>
<code>:max-backlog</code>
</td>
<td>
4096 addresses
</td>
<td>
The fresh queue's capacity. Beyond: <code>:dao.space.dht/backlog-full</code>.
Composition data to <code>join</code>.
</td>
</tr>
<tr>
<td>
<code>:repair-batch</code>
</td>
<td>
64 addresses
</td>
<td>
Most repair-queue entries one publication holds. The repair queue's own capacity
is <code>:max-repairing x :repair-batch</code>.
</td>
</tr>
<tr>
<td>
<code>:max-open</code>
</td>
<td>
16 publications
</td>
<td>
Awaiting a first report. Beyond: the new one is
<code>:dao.space.dht/publications-full</code> and goes to repair.
</td>
</tr>
<tr>
<td>
<code>:repair-slots</code>
</td>
<td>
16
</td>
<td>
Outstanding requests reserved for repair while its queue is non-empty. Below
<code>max-pending-writes</code>.
</td>
</tr>
<tr>
<td>
<code>:repair-ticks</code>
</td>
<td>
30000
</td>
<td>
First repair delay, and the delay after a cycle that sent something.
</td>
</tr>
<tr>
<td>
<code>:repair-max-ticks</code>
</td>
<td>
600000
</td>
<td>
Ceiling of the doubling delay.
</td>
</tr>
<tr>
<td>
<code>:max-repairing</code>
</td>
<td>
16 publications
</td>
<td>
Beyond: the oldest is retired <code>:dao.space.dht/displaced</code>.
</td>
</tr>
<tr>
<td>
<code>:seq-floor</code>
</td>
<td>
0
</td>
<td>
Per declared principal.
</td>
</tr>
<tr>
<td>
Link attempt budget
</td>
<td>
64 drive rounds
</td>
<td>
<code>yin.repl.link/attempt-budget</code>, unchanged.
</td>
</tr>
<tr>
<td>
Name scope
</td>
<td>
HEAD and loaded index manifests
</td>
<td>
Section 7.2.
</td>
</tr>
<tr>
<td>
Cost of one fold
</td>
<td>
Linear in the snapshots' datoms
</td>
<td>
<strong>Performance limit.</strong> <code>names</code>,
<code>resolve-name</code> and <code>dependency-bindings</code> read
<code>&lt;dir&gt;/HEAD</code> from disk and rebuild the HEAD index's datoms on
every fold, and every name a <code>require</code> resolves folds once.
Resolution cost therefore grows with the publisher's own index size. No cache is
kept; one is admissible only if invalidated on every HEAD change and every index
load.
</td>
</tr>
</table>

## 12. Slices

Order: **L0 and L1 -> L2 -> L3 -> L4 -> L5.** L0 and L1 own disjoint files.
Before
L3, the only `yin/repl/*` sources touched are L1's rendering of load
reasons and publication results and its two host-function entries.

**L0 -- publisher, signing, local runtime.** Plain Clojure, host neutral.
Files: `src/cljc/yin/vm/linker.cljc` (`local-runtime`), new
`src/cljc/yin/vm/linker/publish.cljc`, new
`src/cljc/yin/vm/linker/sign.cljc`, `pubspec.yaml` and `pubspec.lock` (the Dart
Ed25519 package and its resolution),
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
- `:links` reports ok on all four formats for the closed corpus. For the store
  corpus it reports the tree format refused `:undeclared-free` naming the read,
  and semantic, stack and register ok.
- An undefined export writes nothing.
- The footprint of 5.2, with no prerequisite outside master: a tree that
  defines and reads store keys answers exactly `ast-requirements`'
  `:store-keys`; an effectful declared primitive and a stream tag each add
  their effects; a required manifest adds its footprint's effects and none
  of its store keys; a missing required manifest, an unprofiled primitive,
  an FFI call and a parked id each refuse with their reason and write
  nothing. The manifest it produces makes
  `yin.vm.completion` report no `:missing :footprints` for the module.
- `yin.vm.linker/local-runtime` exists and the linker tests' runtime delegates
  to it; no behaviour changes. `yin.repl.link` adopts it in L3.

**L1 -- the staged load, the backlog, the publication result.** Files:
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
  repair queue never exceeds `:max-repairing x :repair-batch`;
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
  `max-pending-writes - repair-slots` outstanding and is reported while
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

**L2 -- the closure walker and module load, plain Clojure, by address.**
Files: `src/cljc/yin/vm/linker/publish.cljc`, new
`src/cljc/yin/vm/linker/closure.cljc`, new
`src/cljc/yin/vm/linker/dht.cljc`, new
`test/yin/vm/linker/closure_test.cljc`, new
`test/yin/vm/linker/dht_test.cljc` (over `test/dao/jing/dht/mesh.cljc`).
Acceptance:
- `publish-module!` walks the closure it minted and refuses unless the walk is
  `:complete`; a `:requires` address that holds no valid manifest is refused by
  that walk.
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

**L3 -- the REPL's DHT link source and the relevant re-check.** Files:
`src/cljc/yin/repl/link.cljc`, `src/cljc/yin/repl.cljc`,
`src/cljc/yin/repl/main.cljc`, `src/cljc/yin/repl/dht.cljc`,
`src/cljc/yin/repl/query.cljc`, `test/yin/repl/dht_test.cljc`,
`test/yin/repl/require_test.cljc`. Names are direct addresses in this
slice. Acceptance:
- `yin.repl.link` builds its DHT-source link runtime with
  `yin.vm.linker/local-runtime`. The attempt budget of the `:content-store` and
  `:content-client` sources is unchanged.
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

**L4 -- rows every round, names in the index, publish at the prompt.**
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

**L5 -- end to end.** Files: `test/yin/repl/dht_process_test.clj`, new
`test/yin/vm/linker/dht_end_to_end_test.cljc`,
`docs/agents/build-n-test.md`. Acceptance:
- Process A (`dht:<dir>`, a peer, `--dht-publish`, `--dht-key`) defines a
  function and publishes it as a module. The test reads A's index manifest
  address and principal from A's output.
- Process B (`--dht-manifest`, `--dht-principal`) requires the module by
  name, waits through pending, and evaluates the export, on each of the
  four VMs. B is run on the JVM and on Node.
- Plain Clojure does the same through
  `join -> load-index -> names -> load-module -> link`, the very functions the
  host module calls, and runs the image; it also asserts each failure
  result of section 9 as data.
- Without `--dht-principal`, B's require is `:absent` with an
  `:undeclared-principal` diagnostic.
- The in-process form of the same scenario passes on Dart over the mesh
  seam, signatures verified, on all four VMs.
- The store corpus (an export reading a module-level definition) evaluates on
  the semantic, stack and register VMs and refuses on the walker with the
  linker's reason.

## 13. Deferrals

- **The persistent stepped linker.** `link-manifest` as a state machine the
  ticker advances beside the node, with the node's rings as the content
  pair and no prefetch. The staged load is the interim implementation, and
  its acceptance gate is L2's zero-fetch link.
- **Rendezvous.** A reader is handed every index manifest address it loads
  by hand, and the address of each source it follows. Latest-root discovery
  is no longer deferred: `yin.vm.linker.dht.head.md` is its design.
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
- **Step 5a for module-level reads in the tree format.** Which modules link on
  the walker is the linker's rule, not this epic's.
- The three hosts' verifiers may disagree on adversarial encodings that only
  the key holder can craft (small-order keys, non-canonical points).
- **Host modules as dependencies of a published module.**
- **Availability repair.** Pinning, re-replication of content whose holders
  left, and garbage collection are `dao.jing.dht.md`'s deferrals.
- **A node id derived from the publisher's key** (`dao.jing.dht.md` 6).
- **ShiBi.** Declarations and write capabilities as tuples supplied to the
  composition.
- **Signed names in a browser build.**

## 14. Post-M5 hardening contracts

Status: design contract for M-next; implementation and acceptance evidence
remain required. This section hardens the landed M5 linker and the
linker-over-DHT epic; it does not declare full UCF acceptance. Sections
14.1 and 14.2 close different gates and have independent test evidence.
Stage state (14.3): M-next A, the kept-cursor proof, landed in `80b59233`;
M-next B, the UCF version-1 amendment, is published in the UCF (`0c7ee4ee`);
M-next C to E are not started.

The current UCF 7.11.1 safepoint and portable-encoding rows assign the
string-backed reflection and kept-cursor proof to M4, explicitly removing
its former post-M5 deferral. Section 14.1 preserves that obligation and
adds the ordered cross-host resume matrix. A same-host codec test or a
landed milestone is not evidence for that matrix. The ownership row still
assigns exclusive custody and effect admission to post-M5 hardening.

The existing `yin.vm.linker.dht` loads a module closure onto the node and
links over its local store. `yin.repl.link` serves correlated requests;
a pending DHT load leaves the request cursor unchanged until it answers.
Neither path transfers task custody. Content availability, a signed name,
a loaded snapshot, a successful link, and a DHT acknowledgement establish
no authority to resume an occurrence or commit its effects. The node stays
the sole DHT step owner; the handoff driver steps beside it over streams.

### 14.1 Kept cursors and aliasing across hosts

#### 14.1.1 Acceptance invariant and safepoints

For each ordered source/receiver pair in JVM, Node (CLJS), and Dart (CLJD),
a lower into a fresh task preserves the original logical stream identity,
the source-minted opaque kept position, the cursor-cell alias relation,
and wait-set order. Test all nine pairs, including the three same-host
pairs as controls. Serializing a cursor must not translate it to a local
anchor, offset, newly minted position, or copied stream. Host resource ids
and seals are fresh; their equality across hosts is neither needed nor
permitted as a substitute for logical cell identity.

Every reachable reference to one source cursor resource maps to one cell
and one fresh receiver resource. Distinct cursor resources map to distinct
cells even when their stream and position contents are equal. Advancing a
shared cell is visible to its next waiter in the same polling round;
advancing one independent cell changes no other cell. Wait order comes
from carried scheduler state, not map iteration or sorted cell ids.

| Safepoint kind | Required resume evidence |
|---|---|
| Explicit park | No active wait; reachable cells keep their positions. |
| Blocked read | Poll the remapped cell at its kept position. |
| Blocked write | Retry the retained value on the original target. |
| Sent FFI | Await the original response pair and kept response cell. |
| Retained FFI | Retry the same request, id, args, and response cell. |
| Ordinary/tail effectful call | Use the observed effect's pending shape. |
| `:link-request` | Retry the retained link envelope and id verbatim. |
| `:link-response` | Poll the original response cell; match the link id. |
| `:install` | Preserve child phase and its waits, or refuse export. |
| Halt | Carry a result; create no resumable frame or pending wait. |

For write-only rows the position obligation applies to all reachable
cells, including response cells retained before the append. An outstanding
FFI or link response uses the emitter's pair; a subsequent new operation
uses the receiver's composed pair. A sent request is never reissued merely
because a task moves. A retained request is not recomputed. Reflection
outbound acceptance alone never wakes a writer: source acceptance is the
completion evidence; an unknown append remains undischarged.

Static `:yin.safepoint/kinds` checks pc eligibility only. Dynamic reasons
and pending variants come from observed parked records, wait entries, and
effect outcomes, as ruled in `yin.vm.ucf-revisions.md` section 8. An
explicit park uses the approved no-wait shape. An effectful call must use
its actual read, write, FFI, or link variant; an unspecified
`:call-effect` shape cannot be invented from the static kind. An install
name alone cannot reconstruct an active child; incomplete child evidence
refuses before publication.

#### 14.1.2 State, wire, and lift/lower

The driver retains an export record containing the whole blocked task,
ordered waits, reachable parked records, fresh-name counter, isolated
store and module snapshots, and private resource graph. It also carries
explicit composition state for remote table entries and their lease
routes. Those routes keep the original streams served; this design does
not re-home a stream or promise export after its serving host disappears.

No cursor wire field is added. UCF 7.5.3 already carries
`:yin.k/cells`, each cell's stream marker and `:yin.k/position`, and
`:yin.k/cursor-ref` markers naming cells. Requirements name
`:dao.stream.remote/v1` and every reachable stream identity. Cell ids
are lift-local identities, never content hashes or position hashes.
A cell mapping is shared by frame, waits, stores, closures, and parked
records during one lift; separate encoders per root would erase aliasing.

Lift proceeds as follows:

1. Validate the safepoint and empty ready queue; enter exporting before
   any carrier append. Capture the ordered waits without polling them.
2. Walk the complete reachable graph with one source-resource-to-cell
   map. Authenticate sealed references against the source's private
   resources; forged references refuse with their existing path and kind.
3. Enter each logical stream in the exporter's remote table once. Verify
   portable cursor support and reachable lease-governed routes. Encode
   the returned descriptor and source cursor verbatim, never a handle.
4. Encode pending variants from runtime evidence, close requirements,
   validate the full body, and publish under its content address. Failure
   leaves the source exporting; retry retains its occurrence and cells.

Lower proceeds as follows:

1. Check stamp, body hash, grammar, references, and dependency closure
   before installing a runnable task. Fetch code by verified address;
   a DHT module closure's loaded status does not satisfy cursor routing.
2. Obtain custody when exclusive, then attach each stream identity once
   through its carried descriptor. Missing/gone routes or profiles yield
   `:yin.k/unsatisfied` naming the stream or profile.
3. Allocate one fresh private cursor resource for each logical cell;
   seed it with the carried position without calling `cursor` on an
   anchor. Allocate equal-content distinct cells separately. Re-seal all
   references under the receiving task and remap every root consistently.
4. Restore stores in isolation, counter and parked records, then ordered
   pending waits. Install-child state must pass the same completeness
   gate. Publish the new machine value only after all restoration passes.
5. Poll through the original stream reflection. Return the source's
   value, end, blocked, or gap and successor cursor exactly. On retention
   loss, expose gap; do not replay, silently skip, or invent a value.

Failed lowering exposes no partially runnable task. Temporary attachments
are cleaned up by the composition; acquired custody is released before
returning failure, with a pending release retried by the driver. A dead
receiver is recovered through lapse. No cleanup promise overrides the
remote table's own lease and retention rules.

#### 14.1.3 M-next test contracts

Every row runs on each source host and each receiver host. Transfer actual
canonical bytes through a transport/remote-table seam between fresh
runtimes; carrying an in-process handle or using a shared registry fails
the setup. JVM/Node additionally use separate processes. Dart uses fresh
isolated runtime tables and the portable mesh seam; process transport
coverage is recorded separately, never inferred from mesh coverage.

- Setup: A string-backed local stream has prefix A already consumed and
  B,C unread. Put one shared cell in two ordered waiters and in a closure
  and store, plus two independent cells at the same position. Action:
  export, transfer bytes, attach, lower, and poll. Assert: shared waiters
  see B then C; independent cells each see B. Advancing a closure/store
  alias advances the same cell. No waiter sees A, and original handles,
  ids, and seals occur nowhere in the transferred value.
- Setup: The same task at each row of 14.1.1, with distinct activation
  depths and ordinary/tail calls. Action: hand off, satisfy the wait,
  continue to the next safepoint. Assert: canonical frame, result, error,
  and effect trace equal the reference run; dynamic reason and pending
  variant match observed evidence. Halt remains a result. Incomplete
  install or call evidence refuses without publishing a partial body.
- Setup: Interleave unrelated, duplicate, and late responses around the
  target FFI/link response; append the target while the task is in transit.
  Action: lower with a different receiver pair and poll. Assert: kept
  position finds the exact correlated response once; no sent request is
  reissued; future calls use the new pair. Retained/full requests retry
  the identical envelope and stay parked until source acceptance.
- Setup: Evict B before the receiver's first poll, then separately reclaim
  the remote table entry or a required relay lease. Action: lower/poll.
  Assert: eviction yields the source's gap and exact successor cursor;
  route loss yields unsatisfied naming the stream, with no anchor fallback.
  Omit the cursor profile at lift and assert unsatisfied before publication.
- Setup: Missing, extra, cyclic, or malformed cell references, forged
  resource references, and a correctly hashed malformed body. Action:
  lift/decode. Assert: existing non-portable/undecodable path outcomes;
  no restored wait or committed effect. A non-safepoint pc and queued work
  yield the existing not-at-safepoint and not-quiescent outcomes.

### 14.2 Exclusive custody and effect fencing

#### 14.2.1 Acceptance invariant and authority

On JVM, Node, and Dart, one occurrence O has at most one current admitted
holder at the possessing authority. Copies, alternate content encodings,
carrier retries, and two candidates never create additional subjects.
After reclaim an old process may still compute; it cannot commit a
protected effect. Each enrolled operation id and equal canonical intent
commits at most once across all grants of O. Equal ids with different
intent commit nothing. This is an admission guarantee, not a promise that
unprotected external IO becomes transactional.

> Exactly-once commitment applies to insertion into the enrolled
> ledger-projection stream. It does not extend automatically to effects
> performed by downstream readers.

Forwarding from that stream to an existing FFI or link service is
at-least-once or fail-stop unless that service supplies its own
transactional admission. This is the first realization of an enrolled
boundary, not a rule: a future target that joins the same atomic resource
may conform without being newly minted.

Exclusive requires a durable transactable arbitration space, attributed
grantor/holder events, an authority that possesses the grounded resource
it arbitrates (UCF 7.7.3), and declared consumer enrollment. Signed module
principals and DHT nodes
are not custody principals by implication. The handoff composition supplies
the attribution resolver and authority descriptor explicitly. Without
that authority offer fork only; declare each unprotected stream's
at-least-once or fail-stop behavior. No lease gates stream retention.

An exclusive composition asks the authority's `exclusive-capable?` for
the failure model it requires (M-next C, slice C12): true only for a
file backend whose declared lock kind is not `:none`, on an open,
unpoisoned authority opened through `open!`/`grant/reopen!` with no
poison since, whose declared failure model ranks at or above the
requirement (`:none` < `:process-crash` < `:power-loss`). A memory
backend is never capable. The lock kind and failure model are the
declaration captured at open; neither the lock nor the file is probed
live.

The source exporting gate applies to every resumable row of 14.1.1:
reads cannot consume, blocked writers and retained FFI/link requests cannot
append, direct resume cannot restore reachable parked records, and an
install child cannot keep stepping. Freeze the entire task and its child
work, not only parent registers. Halt emits a result without resumption.
The source must obtain a grant like any receiver to run after an offer.

#### 14.2.2 Runtime, ledger, and wire contract

The handoff driver is an explicit step state with export record, occurrence,
carrier progress, offer progress, proposal, observed grant, pending
release, and successor progress. Persist occurrence and publication/offer
progress before retry after restart. An unknown offer append is treated as
possibly offered: the source stays fenced and reconciles with authority;
local abort is legal only with proof no offer was admitted. Duplicate
offer evidence is idempotent by occurrence and snapshot address.

The authority owns durable occurrence admission records: current epoch,
active lease and attributed holder, offered snapshot variants, open/closed
state, and recorded successor at completion. Queries derive projections
from these records; no second DHT ownership index is stored. Epoch is a
monotone counter advanced atomically on reclaim, never reused after
restart. Initial epoch is zero; grants bind to the current epoch. Regrant
follows reclaim, and closed occurrences cannot receive another grant.
(M-next C, slices C8 and C9.) Nothing is indexed, so the cost is in the
queries: ancestry for an inherited id costs O(chain length x occurrences)
plus content-store reads of the accepted checkpoint (one per variant tried,
until one verifies), and the never-seen check on a report, a closure or an
edge is one O(occurrences) scan; all run under the authority lock.

The arbitration admission resource owns durable dedup records keyed by
`{:yin.k/occurrence O :yin.k/seq n}` containing canonical intent and result:
one logical namespace across every enrolled target taking part in the
guarantee, not a table per consumer (UCF 7.7.8). Target identity is in
the intent, not the key. A consumer that cannot join that atomic
resource cannot claim the cross-target guarantee.
Intent is the canonical data vector `[effect-kind target-identity payload]`,
computed from the actual operation, never accepted from a holder-supplied
hash alone. Correlation ids in FFI/link envelopes are part of payload and
are preserved; they do not replace operation ids. Runtime emission state
keeps the next sequence and any pending operation's assigned id/intent.
Assign once before first append, increment once, and retain across full,
unknown acceptance, migration, and retry. Regrant of the same checkpoint
restarts from its carried sequence; a successor carries the next sequence.
Durable input records preserve read outcomes, correlation results, gap
successors, and their ordering for deterministic recovery of that interval.

(M-next C, slice C10.) **The input protocol.** A holder records each input
by a request, attributed to it by the composition's resolver:

```clojure
{:yin.k/occurrence O :dao.lease/lease L :yin.k/epoch e
 :yin.k/input-seq  k :yin.k/source s    :yin.k/observed v}
```

`s` is `{:yin.k/kind kind :yin.k/name n}`, kind one of `:yin.k/read`,
`:yin.k/ffi-result` and `:yin.k/link-result`; `v` is the outcome as data,
gap successors included. The authority commits it as one fact,
`{:yin.k/custody :yin.k/input :yin.k/occurrence O :dao.lease/lease L
:yin.k/input-seq k :yin.k/source s :yin.k/observed v}` (UCF 7.7.2); the
epoch and holder are `L`'s grant's, so the fact does not repeat them.

- *Ordering.* Each root occurrence has one input sequence, dense in `k`
  from 0 and shared by all its tenures. Install children have none: they
  draw from the root's, in the order the driver delivered their inputs.
- *Tenure first.* An exhausted occurrence answers `:suspended`; a lease not
  granted on O, or an author not its holder, is refused (`:unbound-lease`,
  `:wrong-author`); a lapsed lease or another epoch is `:stale`, even for a
  recorded `k`. A malformed request or an unknown occurrence is refused.
- *Acknowledgment.* With `n` records: `k = n` answers `:recorded`, only
  after the durable commit, or `:suspended` when the count would pass
  2^52-1; `k < n` answers `:replayed` when source and observed equal the
  record's canonical content, and is refused `:input-conflict`, carrying
  both, otherwise; `k > n` is refused `:input-gap`, carrying `n`. Only
  `:recorded` commits. These answers are the authority's `:yin.k/status`
  family, never admission outcomes.
- An input conflict commits nothing and quarantines nothing: an input is
  evidence, not an effect. A quarantined occurrence still records inputs.
- *Frontier.* A lease's frontier is the count of its occurrence's input
  records before its grant. A regranted holder replays records 0 to
  frontier - 1 in order, checks each source, and fails closed on a
  mismatch; from the frontier it observes live and records. An empty
  prefix is distinct from missing evidence, which fails closed. The driver
  delivers an input to the task only once it is recorded or replayed.

The wire grammar for this contract is published as the UCF version-1
amendment (M-next B) and is not restated here:

- UCF 7.2.1: the handoff body, its `:yin.k/version`, the version gate,
  and the custody header. `:yin.k/next-op-seq` is a top-level header
  key beside `:yin.k/id-counter`, because the handoff body flattens
  the scheduler slice.
- UCF 7.4.3: `:yin.k/op-id {:yin.k/occurrence P :yin.k/seq n}` on a
  retained write's pending, the explicit-park shape, and the complete
  install child.
- UCF 7.7.8: the sequence state, the `:yin.k/fenced-v1` envelope, the
  grant epoch binding (`:yin.k/custody :yin.k/bound`), the admission
  check order, and the 2^52-1 bound with its overflow behavior.
- UCF 7.9: the closed `:yin.k/admission` outcomes.

Implementation accepts only what those sections publish. These points
were settled there (r5, revised r6 and r7) and bind this section:

- A carried id names the occurrence it was assigned under. Admission
  checks tenure on the envelope's lease binding. An inherited id must
  be among the retained pendings of the checkpoint granted to the
  holder, children included, with its occurrence an ancestor through
  authoritative completion records. Completion forms one acyclic
  successor chain; an orphan report is not an edge.
- An outcome echoes the envelope's incarnation beside its op id, and
  counts only when attributed to the enrolled consumer or admission
  authority for the target.
- A defective envelope commits nothing and yields a structured
  diagnostic, never an admission outcome.
- A terminal refusal is recorded only when the atomic boundary
  establishes it. Unknown transport acceptance establishes neither
  commitment nor its absence: the writer retains the id and retries
  through the fenced boundary, which replays a result already held.
- Snapshot variants of one occurrence preserve its operation
  baseline; an unreadable accepted checkpoint suspends an inherited
  id's admission and changes no tenure, quarantine, or dedup state.
- An authoritative intent conflict quarantines the occurrence: no
  automatic regrant.
- Enrollment is an attributed authority fact on the arbitration
  medium, keyed by target stream identity; M-next C defines it.

The authority publishes an attributed grant binding containing occurrence,
lease, holder, and `:yin.k/epoch`, alongside the unchanged DaoLease fact.
The binding is admitted in the same transaction as the grant; a reader
cannot infer it from an epoch advertised by the holder. Lease vocabulary
and DaoStream outcome maps gain no keys. The target stream identity and
effect kind come from the enrolled operation's boundary. Credentials are
composition resources, never raw host objects inside UCF program values.

(M-next D9.) On lift, `:yin.k/enrolled` is supplied in the retained
custody header from the ledger reader's fold. It is excluded from the
UCF body; the export record remains serializable. For retained
`:put`, `:ffi-request`, and `:link-request` entries in the root or
install children, an enrolled target without an operation id refuses
as `:yin.k/non-portable`, kind `:unprotected-pending`; an operation
id on an unenrolled target refuses as `:yin.k/unsatisfied`, naming
the stream (UCF 7.7.4).

Epoch and sequence are nonnegative portable exact integers bounded by
2^52-1. Neither wraps, and the two exhaust differently (UCF 7.7.8). An
exhausted sequence assigns no id, appends nothing, and refuses export.
An epoch at the bound stays usable until a reclaim would increment it;
that reclaim ends tenure and permanently exhausts the occurrence, after
which admission answers `:suspended` and no successor is eligible.
A new authority
cannot restart an old occurrence at zero. Loss of recoverable epoch/dedup
state requires fail-stop governance recovery, not automatic exclusive
regrant. Input and dedup records stay durable while any replay is allowed;
collection requires authoritative closure and permanent replay rejection.

#### 14.2.3 Exact handoff and admission steps

1. Quiesce and enter exporting as in 14.1.2. Retain any already assigned
   op id and sequence. Validate portability and completeness. Append code
   and body, then offer O on the declared arbitration medium. Carrier
   publication alone is no offer and no grant. Retry every failed phase
   from its retained progress; never mint another O for this park.
2. A candidate checks stamp, hash, grammar, requirements, and authority
   attachment, then proposes for O. Awaiting proposals return
   `:yin.k/awaiting-grant`; competing candidates return
   `:yin.k/not-holder`. Only an attributed grant and matching epoch
   binding permit activation. Missing authority yields unsatisfied.
3. Restore resources, frame, ordered waits, replay inputs, and sequence in
   a private task, using 14.1.2. Any post-grant failure releases first;
   the source remains exporting. Run every protected effect through the
   fenced writer, including retries performed by polling and child work.
   Merely fencing the eventual register restore is too late.
4. Admission authenticates the holder and atomically checks open O, active
   lease, epoch, op id, and actual intent against the durable dedup record.
   Stale epoch or lease refuses even if a dedup record exists. A current
   duplicate with equal intent returns its stored result without commit;
   divergent intent refuses. A fresh operation commits effect, result,
   and dedup record in that same transaction. Never check remotely then
   perform an uncoordinated local side effect.
5. The enrolled boundary appends results from committed records; a crash
   before result delivery permits redelivery, not another commit. The
   driver advances a pending wait only on its correlated completion.
   Remote outbound ok, a lease stamp, and absence of lapse prove nothing
   about commitment. An unreachable authority suspends protected admission.
6. At the next safepoint or halt, publish the successor, report resumed
   with predecessor O and lease, then release. The authority's transaction
   validates that holder and successor, closes O, and records completion.
   A successor offer is eligible only after that predecessor completion;
   an orphan append/report cannot run a second branch. Closure fences the
   old lease. An authenticated release after failed lower returns O to
   offered through reclaim, not completed: no successor was reported.
7. Reclaim atomically invalidates tenure and advances epoch before any
   new grant. Regrant the last authoritative checkpoint, replaying durable
   inputs and op ids. If replay evidence is unavailable, fail-stop before
   protected effects; divergent same-id intent is an additional guard.
   Grantor restart recovers the durable ledger or reclaims prior tenure
   using recoverable epoch state before grant. Permanent ledger loss
   cannot be repaired by reading carrier history.

Consumer admission outcomes are composition stream data, not new UCF
statuses or link refusals; UCF 7.9 publishes their grammar and the
keys each carries. For M-next their closed dispatch key is
`:yin.k/admission`, with values `:committed`, `:replayed`, `:stale`,
`:intent-conflict`, and `:suspended`. Each carries op id; committed/replayed
carry the recorded result. Stale carries observed epoch/lease state,
intent-conflict carries expected and observed intent, and suspended names
the unavailable authority. Compare these data on every host, never text.
Only committed/replayed can supply an effect's success result.

#### 14.2.4 M-next test contracts

Run each contract with JVM, Node, and Dart as holder and as authority/
consumer over the same portable transactional seam. A seam must model
atomic commitment and durable reopen, not separate mutable check/action
stubs. Also run the ordered cross-host candidate pairs of 14.1.1.

- Setup: Two encodings of O, two candidates, and a wakeable source writer.
  Action: publish/offer, race proposals, poll source, direct-resume it,
  and tick its install child. Assert: one admitted holder; competitors
  await/refuse; source emits nothing. Grant source itself and assert it
  resumes only then. Fork without authority is explicitly labelled fork.
- Setup: Fail each carrier/offer append, including unknown acceptance;
  crash/reopen the export driver after each phase. Action: retry or abort.
  Assert: one O, retained waits/ids, no source effects, duplicate evidence
  creates no second grant. Abort restores local execution only before a
  proven unadmitted offer; uncertain/admitted offers remain fenced.
- Setup: Grant epoch e, delay one protected effect, reclaim and grant e+1.
  Action: deliver the old effect and replay the new holder's equal id and
  intent twice. Assert: old effect is stale; one commit and stored result
  for the current holder. Concurrent reclaim/admission serializes as
  either commit-before-reclaim or refusal-after-reclaim, never both.
- Setup: Crash immediately before/after atomic effect commitment and
  before result append; retain durable intent/result records. Action:
  reopen/regrant and retry. Assert: zero or one commit as appropriate,
  redelivered recorded result, stable ids, and no duplicate side effect.
  External IO outside the transaction earns no exactly-once assertion.
- Setup: Evict a kept-cursor value after a holder crash. Action: recover
  once with durable inputs and once without, causing changed gap/intent.
  Assert: replay reproduces the old intent/result; missing replay fails
  closed; divergent intent at the same id is intent-conflict, no commit.
- Setup: Partition the protected consumer, or a remote holder reaching
  the authority through its front (below), from authority; lose a lease
  fact or a reply, delay renewal, and advance time only by appended ticks.
  Action: continue admission and reclaim/regrant; resend lost requests.
  Assert: protected admission suspends without authority; no inference
  from absent lapse; a resent request replays; a reply not attributed to
  the arbitration identity discharges nothing; DaoLease's
  incomplete-evidence rules and holder bound remain intact.
- Setup: Crash after successor append, after resumed report, after release
  append, and after authoritative closure. Action: reopen authority and
  redeliver evidence. Assert: before closure no successor is eligible;
  recover/regrant the last recorded checkpoint. After closure O never
  grants again and the recorded successor is eligible once. Failure-lower
  release reoffers instead of completing. Lost permanent authority fails
  stop, while a recoverable restart advances epoch before new admission.
- Setup: Post-grant attachment failure, forged holder/epoch binding,
  sequence overflow, and different receiver-local store bindings. Action:
  lower/admit. Assert: failure releases or retains release progress,
  forgery establishes no grant, overflow never wraps, and isolated stores
  preserve resolution. Unenrolled consumers declare their weaker behavior.

(M-next C, slice C11.) **The authority's front.** A holder outside the
authority's process reaches it through a front: plain functions over an
inbound stream the holder writes, a reply stream the front writes, and the
composition's diagnostic stream. One bounded step reads at most n requests
from a held cursor, a gap counting as one read; there is no clock and no
thread. A request is a map dispatching on `:yin.k/request`, over a closed
set, and each kind requires these keys beside `:yin.k/request-id`:

| `:yin.k/request` | Required keys | Answered by |
|---|---|---|
| `:yin.k/offer` | `:yin.k/id`, `:yin.k/bytes`, `:yin.k/medium` | the offer admission, into the composition's content store |
| `:yin.k/proposal` | `:dao.lease/proposal`, `:yin.k/occurrence` | carriage of the lease proposal to the holder's lease-fact medium |
| `:yin.k/resumed` | `:yin.k/report`, `:yin.k/bytes` | the report admission (UCF 7.7.8) |
| `:yin.k/release` | `:dao.lease/lease` | carriage of the release to the holder's lease-fact medium |
| `:yin.k/input` | `:yin.k/input` | the input protocol (14.2.2) |
| `:yin.k/admit` | `:yin.k/target`, `:yin.k/fenced-envelope` | admission at that enrolled target, reading accepted checkpoints from the same content store (UCF 7.7.8) |

Every request carries a holder-chosen `:yin.k/request-id`, opaque to the
front and echoed unchanged in its reply; it is the only correlation handle
a reply carries, and correlation itself is the driver's (stage D). The
front attributes each request by the composition's resolver over the
inbound stream's identity; it never reads an author field in the request,
and it ignores keys it does not require. A request that is not a map,
names a kind outside the set, or lacks a required key is `:malformed`; one
with no resolved author is `:wrong-author`. Either commits nothing,
carries nothing, sends no reply, and appends exactly one diagnostic:

```clojure
{:yin.k/diagnostic :yin.k/defective-request
 :yin.k/defect     :malformed       ; or :wrong-author
 :yin.k/inbound    i                ; the inbound stream's identity
 :yin.k/author     a                ; resolved attribution, if any
 :yin.k/claimed    {...}}           ; its :yin.k/request and request id
```

Claims are nested and the payload is never echoed, as for UCF 7.7.8's
defective envelope. A request whose landed function throws is answered as
`:malformed`: nothing was committed, and the throw is an argument defect
or an implementation fault; the diagnostic's append result is returned
beside it. The front records a thrown append as its own datum
`{:yin.vm.ucf.authority.front/threw true}`, where admission records
`:dao.stream/transport-error`; a composition reading both knows both.

Every other request gets one reply, `{:yin.k/reply k :yin.k/request-id r
:yin.k/answer x}`, where `x` is the landed function's answer unchanged; a
non-holder is refused as a direct call would refuse it. An admit that
produced no admission outcome (a diagnostic, or an unenrolled boundary)
gets no reply. A carried proposal or release answers `{:yin.k/status
:carried}`; the judge's answer to it is learned from the ledger, through
the grant's binding evidence, never from the reply. A holder with no
lease-fact medium is refused `:no-lease-medium`, and a medium that cannot
be named or does not take the append answers `:suspended :uncarried`.
While the authority serves no projection, poisoned or closed, a proposal
or release is not carried and answers `:suspended :unavailable`. A lost
reply is recovered by resending the request: the ledger's dedup answers
`:replayed`, and the judge answers a proposal id once.

A composition that runs the front owes one attribution rule: the resolver
that attributes a holder's inbound stream and the resolver that attributes
that holder's lease-fact medium must name the same author for both. The
front attributes a request to A and carries the fact to A's medium; the
judge must drain that medium as A's. A composition that cannot guarantee
this must not offer exclusive custody.

On the holder's side, a reply or an outcome counts by UCF 7.9's rule: the
composition attributes the stream it was read from to the arbitration
identity, by identity equality. A record from any other author, a forged
`:committed` or `:intent-conflict` included, discharges nothing.

### 14.3 Migration and sequencing

1. M-next A inventories existing M4 proofs and adds 14.1's byte fixtures,
   host matrix, dynamic-reason checks, and refusal cases. Fix any resource,
   pending-child, or reflection gaps before accepting cross-host resume.
   This gate needs no cursor wire amendment and does not depend on full
   custody; run its functional parity under explicit fork/test isolation.
2. M-next B publishes the UCF amendment for sequence/pending operation
   state, fenced-envelope grammar, grant epoch binding, and admission
   outcomes. If the approved explicit-park or complete install-child shape
   is absent, amend UCF 7.4.3 first; never export the name-only install
   sketch as complete task state. Update 7.11's evidence pointers without
   reopening or silently reassigning the existing M4 kept-cursor gate.
   Published 2026-10-04 as UCF amendment r5: 7.2.1, 7.4.3, 7.7.8, 7.9,
   and the version-1 block of 7.11.1. Both 7.4.3 shapes were absent
   from the UCF text and are amended there. This is a document
   change: it lands no code and closes no gate. Revised as r6 after
   the second architect's review and as r7 on its confirmation.
3. M-next C implements durable authority transactions, attribution,
   reclaim epochs, op-id/intent/result records, input replay, and completion
   eligibility. Prove atomicity and reopen tests before enabling enrolled
   consumers. Availability and DHT name signatures do not satisfy this gate.
4. M-next D integrates the handoff driver and fenced writers with every
   pending path, child work, and the source exporting/abort discipline.
   Wire `yin.repl.core`'s handoff composition as UCF 7.11 requires; keep
   plain functions usable without the REPL. Reuse the landed staged
   module-load/link path; do not add a second loader or DHT step owner.
5. M-next E runs both host matrices and the crash/partition suite through
   that composition. Enable exclusive only when authority and declared
   consumers pass their gates. Record per-host results and any unsupported
   composition; do not claim full UCF closure from milestone completion.

Status: M-next A is landed (80b59233). M-next B is the published
amendment above. M-next C, D, and E remain, and with them every test
contract of 14.2.4; nothing here claims full UCF closure.

Two version-0 defects in the landed handoff were found while writing
the amendment. They are post-A defect fixes, each owed a version-0
test, and may be delivered with M-next D. They are preservation and
validation defects of the version-0 reader, not version-1 gaps. They
do not reopen M-next A's kept-cursor evidence and do not move or
reassign the M4 gate.

- Lower assigns an empty wait set for a `:parked` body that also
  carries frames, so those waits are lost (`handoff.cljc`,
  `resume-task`, about line 1386). Required: restore them in order
  (UCF 7.2.1).
- `validate-body` validates the install entries present but does not
  require an entry for every `:install` pending (about line 996), so
  a foreign body bypasses the check that lift makes. Required:
  refuse `:yin.k/undecodable` (UCF 7.4.3).

Sequence/pending fields change the accepted UCF envelope grammar: publish
`:yin.k/version 1` for this amendment and refuse unsupported versions with
profile-mismatch before restoration (published: UCF 7.2.1; the version
raised is the handoff body's own top-level key, not the code stamp's).
Version 0 remains usable under its
published contract for fork; it is not silently upgraded to fenced custody.
Code stamps v3/b2/r2 and module manifest schema 1 stay unchanged because
this amendment changes handoff data and composition admission, not opcode
semantics. Cursor vectors retain their existing bytes. New fenced
continuations hash their complete amended body; no id is preserved across
changed content. An already assigned unprotected operation cannot be made
exactly-once retroactively: activate protection at a new quiescent,
verified checkpoint with a recorded operation baseline, or refuse it.

## Lineage

The split is Datomic's and Unison's at once: facts about code are datoms a
query reads, and code to run is content a hash names. The name layer is the
petname idea made fail-closed: a key is its own name, a reader chooses whose
claims count, and two claims that disagree resolve nothing.
