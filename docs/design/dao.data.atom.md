# dao.data.atom — Distributed Atoms over the DaoJing DHT

Status: proposed design, not implemented. Derived from and subordinate to
[`datom.world.md`](./datom.world.md). Nothing here amends
[`dao.stream.md`](./dao.stream.md), [`dao.jing.md`](./dao.jing.md), or
[`dao.jing.dht.md`](./dao.jing.dht.md); every mechanism below is a
convention over their existing contracts. `dao.data.atom` is a data
structure that consumes DaoJing, not an extension of it: DaoJing still
keeps no roots (§10, OD-1).

**Related documents:**
- [dao.jing.dht.md](dao.jing.dht.md) — the content-addressed network store
  values live in
- [dao.data.btree.md](dao.data.btree.md) — the persistent tree whose
  segments carry large values
- [dao.stream.remote.md](dao.stream.remote.md) — how readers and proposers
  reach an owner's streams
- [dao.lease.md](dao.lease.md) — the grant vocabulary an ownership lease
  would use (Open Decision OD-2)
- [dao.space.transactor.md](dao.space.transactor.md) — the single-writer
  discipline this design generalizes

---

## 1. Summary

A distributed atom is Clojure's atom (one identity, a succession of
immutable values, `swap!` as compare-and-set with retry) stretched across
peers without shared mutable state, distributed locks, or consensus.

It is split along the line Datomic draws and Avout did not:

- **Values** are immutable and content-addressed. Small values are one
  DaoJing segment; large values are `dao.data.btree` trees whose nodes are
  segments. Values live in the DHT and are fetched, verified, and cached
  by any peer.
- **Identity** is one append-only **succession stream** written by exactly
  one peer, the atom's **owner**. Each element is a small record naming
  the current root address. "The current value" is not stored anywhere; it
  is what an interpreter concludes from the newest record its cursor has
  observed.
- **Change** is a **proposal**: a plain-data compare-and-set request that
  any peer appends to the owner's proposal stream. The owner's
  interpreter, the **steward**, accepts or rejects it and says so on an
  answers stream.

The DHT never learns what an atom is. The atom never asks the DHT for more
than bytes by address.

## 2. What Avout taught

Avout (2011, `liebke/avout`) offered `zk-atom`/`zk-ref` with an MVCC STM over
ZooKeeper. Each of its failures becomes a constraint here:

| Avout failure | Constraint in this design |
|---|---|
| Every update serialized and shipped the whole value | Values are persistent trees of content-addressed segments; an update writes only the new leaf-to-root path, and unchanged subtrees are shared by address across peers |
| ZooKeeper used as a data store: 1 MB znodes, leader-bound writes | Values live in the DHT; the owner's stream carries only root addresses (tens of bytes per change) |
| STM retries each cost many network round trips | No distributed transaction. A `swap!` retry is one proposal and one answer; the new segments it wrote are deduplicated by content |
| Partial failure mid-transaction (session expiry, dead lock holder) | No locks and no in-flight distributed state. A proposal is accepted or not in one step of one interpreter; everything else is immutable |
| A local API hid distributed failure | Every outcome is data (`dao.stream.md`, *Result Convention*); staleness is explicit as a stream position |

## 3. The model

### 3.1 An atom is an interpretation, not a cell

`datom.world.md` forbids shared mutable state. There is none here: every
stream is append-only and every value is immutable. The mutable-looking
thing, "the atom's current value", is derived by each reader from the
newest record it has observed, in the same way `dao.space` derives a
database value from a datom log. Two readers at different cursor positions
see two values of the same atom. Neither is wrong: each has made an
observation from its own cursor, and no global view exists to contradict
them (`dao.stream.md`, *Concurrency*).

### 3.2 Identity

An atom's identity is the logical-stream identity of its succession stream
(`dao.stream.md`, *Purpose*, concept 3). It is plain data, stable across
attachment and serialization, and needs no registry. Naming an atom for
humans is discovery's problem (`dao.stream.discovery.md`), not this one's.

An atom is published as a descriptor map:

```clojure
{:dao.data.atom/succession  <remote descriptor>   ; owner #{:reader}
 :dao.data.atom/proposals   <remote descriptor>   ; owner #{:writer}
 :dao.data.atom/answers     <remote descriptor>   ; owner #{:reader}
 :dao.data.atom/store       <dao.jing coordinate>} ; the DHT the values live in
```

### 3.3 The succession record

Every element of the succession stream is exactly one record:

```clojure
{:dao.data.atom/atom     <identity of this succession stream>
 :dao.data.atom/n        n                 ; 0 for the genesis record, then +1
 :dao.data.atom/prev     <record address or nil>
 :dao.data.atom/root     <segment address or nil>
 :dao.data.atom/proposal <proposal id or nil>}   ; nil only for genesis
```

- `:dao.data.atom/root` names the value. Its shape (a single segment or a B-tree
  root) is recorded in the value's own segment, not in the record; see §5.
  `nil` is the value `nil`.
- The record's own **address** is `jing/segment-key` of the record. Anyone
  holding the record can compute it; it is never written into the record
  itself.
- `:dao.data.atom/prev` makes the succession a **hash chain**. The owner also
  materializes each record into the DHT, so any peer can walk an atom's
  history backwards from any record without the owner being reachable.
- Because `:dao.data.atom/n` and `:dao.data.atom/prev` are inside the hashed bytes,
  two records that name the same root at different times have different
  addresses. Compare-and-set against a record address has no ABA problem.

### 3.4 Reading

Deref is a read by the reader's own interpreter:

1. Attach to `:dao.data.atom/succession` through `dao.stream.remote` and hold a
   cursor on it.
2. Drain the cursor to `:dao.stream/blocked`. The last record drained is
   this reader's **head**.
3. Resolve `:dao.data.atom/root` through the reader's DHT content handle. Reads
   are local-first; a miss runs one lookup per missing segment, is
   verified by hashing, and is cached (`dao.jing.dht.md`, *Reads*). A
   B-tree value is restored lazily, so a lookup costs O(log n) segment
   faults, not a whole-value fetch.

The result of a deref is the pair `[head value]`, never the value alone.
The head is the evidence of how fresh the value is.

A reader that cannot reach the owner keeps its last head and can still read
that value from the DHT. The atom is then stale but readable, and the reader
knows exactly which position it is stale at.

`add-watch` is not a callback. A watcher is an interpreter holding a cursor
on the succession stream, and each record it drains is the watch event.

## 4. Change: proposal, judgement, answer

### 4.1 Vocabulary

Proposals and answers are plain data dispatched on `:dao.data.atom/status`:

| Fact | `:dao.data.atom/status` | Author | Required keys |
|---|---|---|---|
| Proposal | `:dao.data.atom/proposed` | proposer | `:dao.data.atom/proposal`, `:dao.data.atom/atom`, `:dao.data.atom/base`, `:dao.data.atom/root` |
| Accepted | `:dao.data.atom/accepted` | owner | `:dao.data.atom/proposal`, `:dao.data.atom/record` (address), `:dao.data.atom/n` |
| Rejected | `:dao.data.atom/rejected` | owner | `:dao.data.atom/proposal`, `:dao.data.atom/cause`, `:dao.data.atom/head` (current record address) |

- `:dao.data.atom/proposal` is an identity the proposer mints, never reuses, and
  retries with. Deduplication is the payload's (`dao.stream.md`,
  *Writing*): the steward answers a proposal id it has already judged with
  the same answer again and does not judge it a second time.
- `:dao.data.atom/base` is the record address the proposer computed from. It is
  the "expected" half of compare-and-set.
- `:dao.data.atom/cause` is one of `:stale` (base is not the head),
  `:unavailable` (the proposed value could not be fetched in full),
  `:invalid` (the owner's validator declined it), or `:malformed`.

### 4.2 `swap!` is a proposer loop

`swap!` keeps Clojure's semantics: the function runs on the proposer, not
on the owner, and is re-run against the newer value on conflict.

1. Deref to get `[head value]`.
2. Compute `value' = (f value)` locally. For a B-tree value this produces a
   new root that shares every unchanged subtree with `value`.
3. Store `value'` through the DHT content handle. Stored segments land in
   the proposer's local store first and replicate to the `k` nearest peers
   on a best-effort basis. Segments shared with `value` are already present
   and cost nothing.
4. Append a proposal with `:base` = head address and `:root` = the address
   of `value'`.
5. Read the answer from `:dao.data.atom/answers`. On `:accepted`, done. On
   `:stale`, advance the succession cursor and go to 1.

`reset!` is the same loop with `f` ignoring its argument. `compare-and-set!`
is one pass with no retry.

No step waits. The loop is an interpreter over two cursors, and in Yin code
it is written with `dao.await`'s `go`/`<!`. A retry under contention costs one
proposal and one answer. The segments written for a rejected attempt are not
wasted: whatever the retry shares with them is already stored.

### 4.3 The steward

The steward is the owner's interpreter over its proposal stream. Each pass
proceeds in this order:

1. **Drain** the proposal cursor to `:dao.stream/blocked`.
2. **Judge** each proposal in stream order against the head *as updated by
   earlier proposals in this pass*:
   - a proposal id judged before gets its earlier answer re-appended;
   - `:base` ≠ head → `:rejected :stale`;
   - **admission**: walk the proposed root and fetch every segment the
     owner does not already hold, pruning at addresses it has (the
     `walk-addresses` prune protocol, `dao.data.btree.md` §5.1). This costs
     the size of the changed path, not the size of the value. If any
     segment cannot be fetched and verified → `:rejected :unavailable`;
   - the composition's validator, if one is composed, declines →
     `:rejected :invalid`;
   - otherwise **accept**: build the next record, append it to the
     succession stream, append the record to the owner's DaoJing intake
     stream so it is materialized into the DHT, and append `:accepted`.
3. The succession append comes before the answer append. An `:accepted`
   answer is never observable for a record that is not on the succession
   stream.

Admission is what makes the owner the durability point. After it, the
owner's local store holds every segment of every root it has accepted. The
DHT's best-effort replication decides how many *other* copies exist, not
whether one exists.

The steward is the only serialization point, and it serializes one atom.
Unrelated atoms have unrelated owners and never coordinate.

### 4.4 Several values, one commit

Clojure's `dosync` over several refs has no counterpart across owners. That
is deliberate: it is the feature whose distributed form sank Avout.

Values that must change together belong to one atom. The atom's value is
then a map (or B-tree) of the parts, and a single accepted proposal changes
them atomically. Choosing what shares an owner is a modelling decision
made up front, not a transaction boundary discovered at runtime.

## 5. Values in the DHT

- **Encoding.** Values are encoded as canonical CBOR (`dao.jing.cbor.md`),
  so an address minted on one host is the address every host mints for the
  same value. This is what makes cross-host deduplication and hash
  verification sound.
- **Small values** are one segment whose payload is the value.
- **Large collections** are `dao.data.btree` trees stored through its
  `IStorage` (`dao.data.btree.md` §5.1). The root segment is
  self-describing: a branch or leaf blob per §5.2. A `sorted-map` is a
  sorted set of `[k v]` entries under a comparator on `k`; a persistent
  hash map would need its own segment layout (Open Decision OD-4).
- **Segment size.** The UDP DHT refuses any message over its 1200-byte
  datagram budget, and oversized segments stay local-only
  (`dao.jing.dht.md`, *Operational limits*). Until fragmentation exists,
  an atom's trees must use a branching factor small enough that every
  node fits, and a single-segment value over the budget is rejected by the
  proposer before it is proposed. The default branching factor of 512 is
  far over budget.

## 6. Invariants

| # | Invariant |
|---|---|
| A1 | Exactly one peer appends to an atom's succession stream. A second writer is a composition defect, never coordinated |
| A2 | Every succession element is one well-formed record whose `:n` is its predecessor's plus one and whose `:prev` is its predecessor's address |
| A3 | A record is appended only after admission has put every segment of its root in the owner's local store |
| A4 | `:accepted` is appended only after its record's succession append answered `:dao.stream/ok` |
| A5 | A proposal id is judged at most once; repeated submissions get the first answer |
| A6 | Compare-and-set is against record addresses, never against root addresses |
| A7 | A deref yields a head and a value together; no API returns a value without the position it was read at |
| A8 | Nothing here mutates DaoJing directly or asks the DHT for anything but content by address |
| A9 | No operation waits; every outcome is data on a stream |

The succession stream must be on a complete-retention transport, for the
reason `dao.space.transactor.md` T18 gives: the steward derives `n` and
`prev` from the retained history on restart. A reader may use an evicting
transport, since the hash chain in the DHT recovers history it missed.

## 7. Failure and ownership

- **Proposer dies mid-swap.** Nothing is held. Its segments are orphaned or
  shared; its proposal is either answered or never read.
- **Owner unreachable.** The atom is read-only for everyone else: readers
  keep the heads they have, and the values stay fetchable from the DHT
  replicas and caches. Proposals queue on the channel or fail as transport
  outcomes. This is the honest cost of having no consensus.
- **Owner restarts.** The steward re-derives `n`, `prev`, and the set of
  judged proposal ids from its retained succession and answers streams.
- **Planned handoff.** The owner appends a final record carrying
  `:dao.data.atom/successor <atom descriptor>` and the successor's genesis
  record names that final record as its `:prev`. Readers follow the
  pointer, so the hash chain stays unbroken across owners.
- **Unplanned failover** is out of scope; see OD-2.
- **Equivocation is detectable.** If A1 is violated, two records share a
  `:prev`. Any reader that sees both holds verifiable proof of the fork.

## 8. Relationship to `dao.space`

`dao.space.transactor` already has this shape: a single writer, an
append-only local log, immutable B-tree segments published to DaoJing, and a
manifest address that plays the role of a root. A `dao.space` database is,
structurally, an atom whose value is a manifest. This design does not merge
the two. It records that a later unification would be a renaming, not a
redesign, and that anything learned here (admission, segment-size limits,
GC roots) applies to published indexes too.

## 9. Explicitly absent

- Coordinated change across atoms with different owners (`dosync`).
- Consensus, leader election, or automatic failover.
- Exactly-once delivery; deduplication is by proposal id.
- Running `f` on the owner. Transaction functions are OD-3, not the
  default.
- Access control. Until ShiBi exists, anyone who learns the proposals
  descriptor can propose; a composition that must prevent that composes a
  gate on the stream (`dao.stream.remote.md` §7).

## 10. Open decisions

**OD-1. Name and placement (decided).** The namespace is `dao.data.atom`,
beside `dao.data.btree`, and its keywords live under `:dao.data.atom/`.
`dao.data.md` places specific data structures in `dao.data.*`
sub-namespaces, and an atom is one: a persistent identity over immutable
values, as the B-tree is a persistent collection over immutable segments.
Placing it here also keeps it off DaoJing's side of the storage boundary,
so it does not give DaoJing a mutable root. `dao.jing.md` still holds: DaoJing
keeps no roots, CAS records, or deletes, and `dao.jing/get` still rejects
anything that is not a content address. An atom's head lives on the
owner's succession stream, and the steward's compare-and-set is a judgement
by the owner's interpreter, not an operation of the content store.
`dao.data.atom` consumes DaoJing's contract the way `dao.data.btree`'s
`IStorage` does; it adds nothing to it.

**OD-2. Unplanned failover.** An ownership lease (`dao.lease.md`) could let a
successor take over when the owner falls silent. But a lease judge must
possess the resource, and "the right to extend this chain" is possessed by
no one unless a grantor peer is designated. That grantor is a coordinator,
and a quorum of grantors is consensus. The options are: accept read-only
atoms on owner loss; or allow an opt-in, per-atom grantor; or neither. A
split brain between an old owner and a lease-granted successor is
detectable by A1's equivocation evidence but is not prevented.

**OD-3. Transaction functions.** Proposals could carry a Yin continuation
for the steward to evaluate against the head, removing retry under
contention at the cost of executing proposer code on the owner. This needs
ShiBi metering before it is safe to offer.

**OD-4. Hash maps.** Whether unsorted maps get a HAMT-over-segments layout or
are always represented as sorted B-trees.

**OD-5. Retention and GC.** An atom's live roots are its heads. Marking from
them with `walk-addresses` is well defined; sweeping a shared DHT is not,
because the DHT has no pinning or eviction policy
(`dao.jing.dht.md`, *Storage economics / GC*). Until one exists, every
superseded value accumulates.

## 11. Prerequisites and phasing

The DHT's current limits decide what can be built now:

| Limit (`dao.jing.dht.md`) | Effect on atoms |
|---|---|
| 1200-byte datagrams, no fragmentation | Small branching factor mandatory; single-segment values capped |
| No GC or pinning | Unbounded growth of superseded values (OD-5) |
| Dead peers stay in the lookup shortlist | Admission and cold reads can stall on timeouts |
| JVM only, no NAT traversal | Owners and DHT peers must be JVM peers with direct UDP reachability; browsers can read only through a JVM peer's remote streams |
| Replication blocks `put` | Each `swap!` attempt pays up to `k` bounded replication calls |

Phases:

1. **Local.** Record, proposal, and answer vocabulary; the steward pass;
   the proposer loop; all over `memory-log` streams and a
   `dao.jing.mem` store in one process. The invariants in §6 become the
   test suite.
2. **Remote streams.** Readers and proposers on other peers via
   `dao.stream.remote`, with values still in a shared local store.
3. **DHT values.** Values stored via `create-content-dht-udp`, with
   admission fetching deltas, B-trees at a budget-safe branching factor,
   and records materialized into the hash chain.
4. **Large values.** Default branching factor restored once DHT
   fragmentation lands.

## 12. Lineage

- **Clojure atoms** supply the semantics: one identity, immutable values,
  `swap!` as compare-and-set with a local retry.
- **Datomic** supplies the split: a single serializing writer, immutable
  segments in storage, peers that read and cache directly.
- **Plan 9's Venti and Fossil** supply the storage shape: content-addressed
  archival blocks under a small mutable root pointer. Here, even the root
  pointer is an append-only record.
- **Git refs** supply the history shape: a named head over a hash chain of
  commits, where a fork is visible as two children of one parent.
- **Avout** is the negative example (§2).
