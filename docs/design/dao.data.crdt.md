# dao.data.crdt — CRDTs over Stigmergy, and Atoms on Top

Status: proposed design, not implemented. Derived from and subordinate to
[`datom.world.md`](./datom.world.md). Nothing here amends
[`dao.stream.md`](./dao.stream.md), [`dao.jing.md`](./dao.jing.md), or
[`dao.jing.dht.md`](./dao.jing.dht.md); every mechanism below is a
convention over their existing contracts. `dao.data.crdt` and
`dao.data.atom` are data structures that consume DaoJing, not extensions
of it: DaoJing still keeps no roots (§15, OD-1).

**Related documents:**
- [dao.space.md](dao.space.md), *Coordination: Stigmergy* — the
  coordination model this design applies
- [dao.jing.dht.md](dao.jing.dht.md) — the content-addressed network store
  records and values live in
- [dao.data.btree.md](dao.data.btree.md) — the persistent tree whose
  segments carry large values and snapshots
- [dao.stream.discovery.md](dao.stream.discovery.md) — how a reader finds
  the streams it folds

---

## 1. Summary

Replicated data types (counters, sets, registers, maps, text) and
Clojure-style atoms, shared across peers with no owner, no locks, and no
consensus.

The design has three layers, and only the top one is ever optional:

1. **The medium is already a CRDT.** Each participant deposits
   immutable, hash-linked **records** in its own append-only stream.
   Records are content-addressed, so the set of all records is a grow-only
   set: it converges however records are ordered, duplicated, or delayed.
   Coordination is stigmergic, as everywhere in datom.world
   (`dao.space.md`, *Coordination: Stigmergy*).
2. **Every data type is a pure fold over that set.** Operations are
   records; state is the fold of the records a reader has observed; a
   snapshot is a cached fold. Nothing but records is ever authoritative
   (*Derive, don't persist*, `datom.world.md`).
3. **Coordination is opted into, never assumed.** The CALM theorem draws
   the line (§8). Monotone behaviour needs no coordination and gets none.
   Non-monotone invariants (a balance never below zero, a name taken
   once) use escrow or a **sequenced** object, chosen in the object's
   genesis record and paid for there.

`dao.data.atom` is a thin API over this: a coordination-free atom is a
multi-value register (§7.1), and a linearizable atom is a sequenced
register (§7.2).

## 2. What Avout taught

Avout (2011, `liebke/avout`) offered distributed `zk-atom`/`zk-ref` with an
MVCC STM over ZooKeeper. Each of its failures becomes a constraint here:

| Avout failure | Constraint in this design |
|---|---|
| Every update serialized and shipped the whole value | An update is one small record of operations; large values and snapshots are persistent trees whose unchanged subtrees are shared by address |
| ZooKeeper used as a data store: 1 MB znodes, leader-bound writes | Records and values live in the DHT; each participant writes only its own stream |
| Coordination by distributed locks and a leader | None by default. Where coordination is required it is explicit, per object, and named in the genesis record |
| STM retries each cost many network round trips | Coordination-free operations never retry. They cannot conflict, only be concurrent |
| Partial failure mid-transaction | Nothing is ever held. A deposit is one append to one's own stream |
| A local API hid distributed semantics | Every type states its concurrency semantics; a read returns the frontier it was computed from; an atom that cannot promise linearizability does not offer it |

## 3. The medium

### 3.1 Objects and genesis

A replicated object's identity is the address of its **genesis record**:

```clojure
{:dao.data.crdt/genesis <nonce>        ; makes the address unique
 :dao.data.crdt/type    <type>}        ; §5; may be nested, e.g. a map's
                                       ; value type
```

A sequenced object's genesis also names its sequencer (§9). The identity is
self-certifying, owned by nobody, and needs no registry. Anyone may create
an object by depositing a genesis record. Naming it for humans is
discovery's problem.

### 3.2 Records

Every change is one record, deposited as one element of its author's own
stream:

```clojure
{:dao.data.crdt/object <genesis address>
 :dao.data.crdt/prev   #{<record address> ...}   ; the frontier it saw
 :dao.data.crdt/ops    [<op> ...]}               ; §5; at least one
```

- A record's **address** is `jing/segment-key` of the record. Anyone
  holding the record can compute it; it is never written into the record.
- An operation's **id** is `[record-address index]`. Ids are unique
  because record addresses are, and they are what later operations name
  (an OR-Set remove, an RGA insert position).
- `:prev` is the set of records the author had observed as the object's
  heads. The records form a **Merkle DAG**, which is the object's causal
  clock: record `a` **happened before** `b` exactly when `a` is reachable
  from `b` through `:prev`, and two records are **concurrent** when
  neither is reachable from the other. No vector clocks, replica ids, or
  timestamps are needed.
- Batching many operations into one record (a typing burst, a bulk
  import) costs one address and one `:prev` and changes no meaning.
- Records carry no author and no timestamp. Who deposited a record is the
  medium's attribution (the stream it is on).
- The author materializes each record into the DHT, so any peer can walk
  an object's history from any record it holds, without the author being
  reachable.

### 3.3 Observation and counting

A reader holds cursors on an explicit **source pool** of streams, as a
`dao.space.query` reader folds an explicit collection of sources. Finding
the pool is discovery's job: stigmergic membership (follow datoms) and DHT
rendezvous topics keyed by the genesis address are the mechanisms
`dao.stream.discovery.md` already names. Which participants a reader folds
is its trust decision.

A record **counts** for a reader once the reader also holds all of its
`:prev` records and every segment its operations reference; before that
it is treated as not yet observed. Counting is what keeps the DAG closed
under causality: a reader never folds an effect without its causes.

The counted records are the reader's **observed set**, and its
**frontier** is the counted records no other counted record names in
`:prev`.

### 3.4 Why this converges

The observed set only grows, and sets merge by union: this is a grow-only
set CRDT, convergent by construction. Every type in §5 is a deterministic
function of the observed set alone. So any two readers that have counted
the same records compute the same state, whatever order they received
them in and whoever sent them. That is strong eventual consistency, with
no step in which anyone asks anyone anything.

## 4. Folds

A **fold** turns an observed set into a state. Folds come from a closed
registry named by the genesis `:type`, so every reader agrees on them
without asking, and no reader runs code received from the medium.

A fold may use only:

- the operations of counted records;
- the happened-before relation of §3.2;
- a **canonical linearization**, when it needs a total order: a
  topological order of the DAG with ties broken by ascending record
  address. It is deterministic and respects causality; between concurrent
  records it is arbitrary but agreed.

A fold's result is the object's value **for that reader**, returned
together with the frontier it was computed from. Reads never return a
state without its frontier.

## 5. The type registry

Coordination-free types. Every operation below commutes with every
concurrent operation under its fold.

| `:type` | Operations | Fold |
|---|---|---|
| `:counter` | `{:inc n}`, n > 0 | sum of all increments. No per-replica slots are needed: the observed set already removes duplicates |
| `:pn-counter` | `{:inc n}`, `{:dec n}` | sum of increments minus sum of decrements |
| `:g-set` | `{:add x}` | every added element |
| `:or-set` | `{:add x}`; `{:remove #{<op id> ...}}` | an element is present while some `:add` of it is not named by any `:remove`. A remove names the add operations its author had observed, so an add concurrent with the remove survives (add wins) |
| `:mv-register` | `{:set v}` | the last `:set` of each frontier record, **as a set** (the genesis alone gives `#{nil}`). One value when there was no concurrency; several when there was, left for the reader to resolve |
| `:lww-register` | `{:set v}` | the `:set` that is last in the canonical linearization. Concurrent writes resolve by address: arbitrary, agreed, and lossy by design |
| `[:map <value-type>]` | `{:key k :op <op of value-type>}` | a map from each key to the fold of that key's operations under the value type |
| `:rga` | `{:insert v :after <op id or :start>}`; `{:delete <op id>}` | a sequence (RGA): each element is placed after its anchor, siblings with the same anchor in descending canonical order, and deleted ids are hidden but kept as anchors |

The registry is closed so that a fold is data every participant already
agrees on. Adding a type is a design change to this document, not a
runtime act (OD-2).

**Values.** An operation's value is either inline plain data or a segment
address. Large values (a document body, a big set's contents) are stored
as `dao.data.btree` segments and referenced by address, and a record
referencing them counts only once they are held (§3.3).

## 6. Snapshots

A fold over a long history is expensive, so its result can be cached as a
**snapshot**:

```clojure
{:dao.data.crdt/object   <genesis address>
 :dao.data.crdt/frontier #{<record address> ...}
 :dao.data.crdt/state    <segment address>}   ; the fold's result, as segments
```

- A snapshot is a pure function of its frontier: the fold of everything
  reachable from it. Anyone may compute one and deposit it in their own
  stream, and any reader can check one by recomputing it.
- A read is then: the newest usable snapshot, plus the fold of the counted
  records not reachable from its frontier. Every fold in §5 can resume
  from its own state this way.
- A reader trusts snapshots only from streams in its pool, or verifies
  them. A snapshot never adds information; it only saves work (OD-5).
- Snapshot states are B-trees, so successive snapshots share every
  unchanged subtree.

## 7. Atoms

`dao.data.atom` is the Clojure-shaped API over registers. There are two
kinds, and the difference is stated at creation, not discovered under load.

### 7.1 The coordination-free atom (`:mv-register`)

- `deref` returns `{:values #{...} :frontier #{...}}`. `:values` has one
  element unless concurrent writes happened.
- `swap!` takes a **resolver** as well as `f`: read the values, resolve
  them to one (the application's choice: pick one, merge them, or ask the
  user), compute `(f resolved)`, and deposit `{:set v'}` with `:prev` =
  the whole observed frontier. Because it names every head, the new record
  replaces all of them.
- Nothing is lost silently. Two concurrent swaps leave two values visible
  in the next deref, and the next swap resolves them explicitly. A swapper
  that goes away leaves its value in the frontier, where every reader
  still sees it.

This is Dynamo's and Riak's answer, applied through the medium: a fork is
visible data, and resolving it is itself one more trace.

### 7.2 The linearizable atom (`:sequenced` register)

When "exactly one value, in one agreed order, and `swap!` means
compare-and-set" is required, the atom is a sequenced object (§9) whose
operations are `{:cas <address of the last admitted record, or nil> :set v}`.
The sequencer admits a CAS only if the record it names is still the last
one admitted in the sequencer's order. Comparing against a record rather
than a value rules out ABA: the same value set twice is two different
records. Readers following the sequencer get Clojure atom semantics. The
cost is explicit: while the sequencer is absent, the atom accepts no
changes.

The name `atom` with an unqualified `swap!` promise belongs only to this
kind. The coordination-free kind is always used with a resolver, so its
semantics cannot be mistaken for linearizability.

## 8. The boundary: CALM

The CALM theorem (Hellerstein and Alvaro) states that a program has a
consistent, coordination-free distributed implementation **if and only if
it is monotone**: new information can add to its conclusions but never
retract one.

| Need | Monotone? | Mechanism |
|---|---|---|
| Counting, accumulating, collecting | yes | §5 types |
| Add and remove with add-wins semantics | yes, in the OR-Set's formulation | `:or-set` |
| Collaborative text, maps of the above | yes | `:rga`, `[:map …]` |
| A value that shows conflicts rather than hiding them | yes | `:mv-register` |
| Balance ≥ 0, stock ≥ 0, quota | no | escrow (§8.1), else sequenced |
| A name or claim taken exactly once | no | sequenced (§9) |
| Linearizable compare-and-set | no | sequenced atom (§7.2) |

The same line runs through `dao.space`. `q` over positive Datalog is
monotone and safe over any pool; `not` / `not-join` is not, and its answers
can be withdrawn as more records arrive. This design and the query engine
draw the boundary in the same place.

### 8.1 Escrow

Many invariants can be made monotone ahead of time. A bounded quantity is
split into per-participant **allowances**, declared in the object's
genesis record. A participant spends only within its
own allowance, so it never needs to ask anyone, and the global bound holds
because the sum of allowances respects it. Rebalancing is a transfer
between allowances: a record from the giving participant naming the
receiver. This keeps the common path coordination-free and leaves only
rebalancing, rarely, to coordinate.

## 9. Sequenced objects

A sequenced object is where coordination is opted into. It remains
stigmergic, because nobody addresses anybody:

- The genesis record names a **sequencer**: the logical-stream identity of
  one participant's stream.
- Any participant deposits **requests** (ordinary records carrying the
  object's operations) in its own stream.
- The sequencer's interpreter observes requests in its pool, decides the
  order, and deposits **order records** in its own stream:
  `{:dao.data.crdt/object o :dao.data.crdt/admit [<record address> ...]}`,
  or a refusal naming the record and a cause.
- The fold of a sequenced object uses only the order the sequencer
  published. A request's author learns the outcome by observing the
  sequencer's stream, not by being answered.

This is the pattern `dao.stream.discovery.md` already describes for a
directory owner: requests are deposited in the requester's own stream, the
owner observes them and appends the binding to its own. It is linearizable
for every reader of the sequencer's stream, and unavailable for changes
while the sequencer is absent. Unplanned failover is not provided (OD-4).

## 10. Records and values in the DHT

- **Encoding.** Records, snapshots, and values are canonical CBOR
  (`dao.jing.cbor.md`), so every host mints the same address for the same
  content. The canonical linearization compares addresses, so this is
  also what makes hosts agree on an order.
- **Availability.** Every author's own store holds every segment it
  deposited, because DHT writes land locally first. Replication decides how
  many other copies exist. A reader that cannot fetch something does not
  count the record that needs it (§3.3), which delays its view but never
  changes what it computes from what it has counted.
- **Segment size.** The UDP DHT refuses messages over its 1200-byte
  datagram budget (`dao.jing.dht.md`, *Operational limits*). Until
  fragmentation exists, records must be batched within the budget, and
  B-tree values and snapshots must use a branching factor small enough
  that every node fits. The default of 512 is far over budget.

## 11. Invariants

| # | Invariant |
|---|---|
| C1 | A participant deposits records only in its own stream; no stream has two writers, and no participant addresses or waits on another |
| C2 | Records, snapshots, and values are immutable and content-addressed |
| C3 | A record counts only once its `:prev` records and every segment its operations reference are held |
| C4 | A fold is a deterministic function of the counted records: readers that count the same records compute the same state |
| C5 | Folds come from the closed registry named in the genesis record; no reader evaluates code taken from the medium |
| C6 | A coordination-free type's operations commute with every concurrent operation under its fold |
| C7 | Every read returns its state with the frontier it was computed from |
| C8 | A sequenced object's fold uses only the order its named sequencer published |
| C9 | A snapshot equals the fold of everything reachable from its frontier; a reader that relies on one either trusts its stream or has verified it |
| C10 | Nothing here mutates DaoJing directly, and no operation waits: every outcome is data on a stream |

## 12. Costs

- **Metadata that is never collected.** Removes keep naming what they
  removed, and RGA keeps deleted elements as anchors. Discarding them
  safely requires knowing every participant has seen an operation (causal
  stability). With open membership nobody can know that, so these grow
  without bound (OD-3).
- **Views differ.** Readers with different pools, or different progress
  through the same pools, see different states. Every read says which.
- **Anyone in a reader's pool can write.** Until ShiBi exists, the trust
  boundary is which streams the reader folds. A coordination-free type has
  no fork to game, but it still counts every operation it is shown.
- **`:lww-register` loses data by design.** It is offered for values where
  that is acceptable (a cursor position, a presence status) and nowhere
  else.

## 13. Relationship to `dao.space`

`dao.space` already coordinates this way: agents deposit datoms in their own
streams and derive current state read-side. Its current-state resolution
treats assertions and retractions of one `[e a v]` much as an OR-Set treats
adds and removes, and a published index is a snapshot whose manifest
address is its state. This design does not merge the two. It records that
datoms could become one more folded type later without a redesign, and
that anything learned here (counting, causal stability, the CALM line)
applies to `dao.space` too.

## 14. Prerequisites and phasing

The DHT's current limits decide what can be built now:

| Limit (`dao.jing.dht.md`) | Effect |
|---|---|
| 1200-byte datagrams, no fragmentation | Records batched within budget; small branching factor for values and snapshots |
| No GC or pinning | Superseded snapshots and values accumulate (OD-3) |
| Dead peers stay in the lookup shortlist | Counting and cold reads can stall on timeouts |
| JVM only, no NAT traversal | Participants that fetch from the DHT must be JVM peers with direct UDP reachability |
| Replication blocks `put` | Each deposit pays up to `k` bounded replication calls |

Phases:

1. **Local folds.** Genesis and record vocabulary, counting, the canonical
   linearization, and the §5 registry over `memory-log` streams and a
   `dao.jing.mem` store in one process. Each type gets a property test:
   every interleaving of the same records folds to the same state.
2. **Atoms and snapshots.** `dao.data.atom` over `:mv-register`; snapshots
   and resumed folds.
3. **Sequenced objects.** The sequencer interpreter, order records, and the
   linearizable atom.
4. **Remote and DHT.** Participants on other peers through
   `dao.stream.remote`; records and values via `create-content-dht-udp`;
   source pools found through discovery.
5. **Escrow.** Allowances and transfers for bounded counters.

## 15. Open decisions

**OD-1. Name and placement (decided).** Replicated types live in
`dao.data.crdt` and the atom API in `dao.data.atom`, beside
`dao.data.btree`. `dao.data.md` places specific data structures in
`dao.data.*` sub-namespaces. Placing them here keeps them off DaoJing's
side of the storage boundary: DaoJing keeps no roots, CAS records, or
deletes, and these namespaces consume its contract the way
`dao.data.btree`'s `IStorage` does, adding nothing to it.

**OD-2. Registry contents.** Whether the §5 set is right for a first
version, and which types come next (a multi-value map, a JSON-shaped
document type, datoms).

**OD-3. Causal stability and GC.** Whether a participant set declared in a
genesis record (closed membership, per object) should enable tombstone
collection once every declared participant's frontier covers an
operation, and how that interacts with the DHT's absent GC.

**OD-4. Sequencer failover.** A designated successor, an ownership lease
(`dao.lease.md`, which needs a grantor that possesses the resource), or
none. Each option above "none" reintroduces some coordinator.

**OD-5. Finality and snapshot trust by redundancy.** A snapshot or order
record could be treated as settled once enough independent streams in a
reader's pool have deposited attestations naming it. This is agreement
through redundant traces, as in Zurek's quantum Darwinism, where
objectivity comes from many observers reading redundant records rather
than from a coordinator. It is a quorum chosen per reader, built from
ordinary deposits, and not part of this design yet.

## 16. Lineage

- **Stigmergy and the tuple space** (blackboard → Linda → `dao.space`):
  participants act on traces in a shared medium, never on each other.
- **CRDTs** (Shapiro, Preguiça, Baquero, Zawirski, 2011) supply the types;
  **pure operation-based CRDTs** (Baquero, Almeida, Shoker) supply
  "operations in a causal log, state as a fold".
- **Merkle-CRDTs** (Sanjuán, Pöyhtäri, Teixeira, Psaras) supply the Merkle
  DAG as causal clock, carried over a content-addressed network.
- **RGA** (Roh et al.) supplies the sequence type.
- **CALM** (Hellerstein, Alvaro) supplies the boundary between what needs
  coordination and what does not.
- **Dynamo and Riak** supply the multi-value register: show siblings,
  resolve on the next write.
- **Datomic** supplies immutable segments in storage and peers that read
  and cache directly.
- **Clojure atoms** supply the API shape, kept only where their promise can
  be kept.
- **Avout** is the negative example (§2).
