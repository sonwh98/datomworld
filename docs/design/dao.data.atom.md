# dao.data.atom — Distributed Atoms over the DaoJing DHT

Status: proposed design, not implemented. Derived from and subordinate to
[`datom.world.md`](./datom.world.md). Nothing here amends
[`dao.stream.md`](./dao.stream.md), [`dao.jing.md`](./dao.jing.md), or
[`dao.jing.dht.md`](./dao.jing.dht.md); every mechanism below is a
convention over their existing contracts. `dao.data.atom` is a data
structure that consumes DaoJing, not an extension of it: DaoJing still
keeps no roots (§11, OD-1).

**Related documents:**
- [dao.jing.dht.md](dao.jing.dht.md) — the content-addressed network store
  values and records live in
- [dao.data.btree.md](dao.data.btree.md) — the persistent tree whose
  segments carry large values
- [dao.space.md](dao.space.md), *Coordination: Stigmergy* — the
  coordination model this design applies
- [dao.stream.discovery.md](dao.stream.discovery.md) — how a reader finds
  the streams it folds (stigmergic membership, rendezvous topics)

---

## 1. Summary

A distributed atom is Clojure's atom (one identity, a succession of
immutable values, `swap!` as compare-and-set with retry) stretched across
peers with no shared mutable state, no owner, no locks, and no consensus.

The DHT does not coordinate, and neither does any peer. Coordination is
stigmergic, as everywhere else in datom.world (`dao.space.md`,
*Coordination: Stigmergy*):

- **Values** are immutable and content-addressed. Small values are one
  DaoJing segment; large values are `dao.data.btree` trees whose nodes are
  segments. They live in the DHT and are fetched, verified, and cached by
  any peer.
- **Changes are traces.** A participant changes an atom by depositing a
  **record** in its *own* stream. The record names the new value's root
  and the records it was built on. Nobody is asked and nobody answers.
- **The value is an interpretation.** A reader folds the records it has
  observed into a DAG, and a pure, deterministic **resolution** of that DAG
  is the atom's value for that reader. Two readers that have observed the
  same records compute the same value.
- **A fork is the pheromone.** Concurrent changes leave two heads in the
  DAG. The fork is visible to everyone as data, and it is what prompts
  participants to act: a swapper whose change lost re-applies its
  function; anyone may deposit a record that joins the heads.

The DHT never learns what an atom is. The atom never asks the DHT for more
than bytes by address.

## 2. What Avout taught

Avout (2011, `liebke/avout`) offered `zk-atom`/`zk-ref` with an MVCC STM over
ZooKeeper. Each of its failures becomes a constraint here:

| Avout failure | Constraint in this design |
|---|---|
| Every update serialized and shipped the whole value | Values are persistent trees of content-addressed segments; an update writes only the new leaf-to-root path, and unchanged subtrees are shared by address across peers |
| ZooKeeper used as a data store: 1 MB znodes, leader-bound writes | Values live in the DHT; a change deposits one small record in the writer's own stream |
| Coordination by distributed locks and a leader | No locks, no leader, no owner. Coordination is read-side: every participant resolves the same traces the same way |
| STM retries each cost many network round trips | A retry is a local recomputation and one deposit; segments it shares with the lost attempt are already stored |
| Partial failure mid-transaction (session expiry, dead lock holder) | Nothing is ever held. A deposit is one append to one's own stream; everything else is immutable |
| A local API hid distributed failure | Every outcome is data (`dao.stream.md`, *Result Convention*); a deref states which records it resolved |

## 3. The model

### 3.1 An atom is an interpretation, not a cell

`datom.world.md` forbids shared mutable state. There is none here: every
stream is append-only and single-writer, and every value and record is
immutable. The mutable-looking thing, "the atom's current value", is
derived by each reader from the records it has observed, the way
`dao.space` derives current state from accreted datoms (`dao.space.md`: to
claim work an agent *appends* a claim, and "current state" is a read-side
query). Two readers that have observed different records see two values
of the same atom. Neither is wrong, and neither is authoritative.

### 3.2 Identity

An atom's identity is the address of its **genesis record**:

```clojure
{:dao.data.atom/genesis  <nonce>              ; makes the address unique
 :dao.data.atom/resolve  <resolution keyword> ; §3.5
 :dao.data.atom/root     <segment address or nil>}
```

The identity is self-certifying: anyone holding the genesis record can
check it against the address. It is plain data, owned by nobody, and needs
no registry. Anyone may create an atom by depositing a genesis record.
Naming it for humans is discovery's problem (`dao.stream.discovery.md`),
not this one's.

### 3.3 The record

Every change is exactly one record, deposited as one element of its
author's own stream:

```clojure
{:dao.data.atom/atom  <genesis address>
 :dao.data.atom/prev  #{<record address> ...}  ; the heads it was built on
 :dao.data.atom/root  <segment address or nil>}
```

- `:root` names the value. Its shape (a single segment or a B-tree root)
  is recorded in the value's own segment, not in the record; see §5.
- A record's address is `jing/segment-key` of the record. Anyone holding
  the record can compute it; it is never written into the record itself.
  The genesis record is the one record with no `:prev`.
- `:prev` is a set: the records form a **Merkle DAG**, not a chain. A
  record with one `:prev` is an ordinary change; a record with several is
  a **join**.
- The author also materializes each record into the DHT, so any peer can
  walk an atom's history backwards from any record it holds.
- Records carry no author, no sequence number, and no timestamp. Who
  deposited a record is the medium's attribution (the stream it is on),
  and its causal position is its `:prev`.

### 3.4 Observation

A reader holds cursors on an explicit **source pool** of streams, the same
way a `dao.space.query` reader folds an explicit collection of sources.
How the pool is found is not this design's concern: stigmergic membership
(follow datoms) and DHT rendezvous topics keyed by the genesis address are
the mechanisms `dao.stream.discovery.md` already names. Which participants
a reader folds is its trust decision.

The reader drains its cursors and collects every record that names this
atom. A record **counts** for this reader once it has also fetched the
record's `:prev` records and the record's root in full; before that it is
treated as not yet observed. The counted records form the reader's
**observed DAG**, and its **heads** are the counted records that no other
counted record names in `:prev`.

### 3.5 Resolution

Resolution is a pure function from an observed DAG to a value. It is named
in the genesis record by a keyword from a closed registry, so it is data
every participant agrees on without asking anyone, and no reader ever runs
code it received from the medium:

- **`:dao.data.atom/linear`** gives compare-and-set semantics. Exactly one
  head **wins**: the head with the greatest depth (longest path to
  genesis), ties broken by the greatest record address. The value is the
  winner's root.

  The **winning line** runs from the winner back to genesis. At every
  record with several `:prev`, the line continues through the parent the
  same rule picks among those `:prev` (greatest depth, then greatest
  address). A record's change **is in the value** exactly when the record
  is on the winning line. Every other record **lost**, even when a later
  record names it in `:prev`: naming a record records that it was seen,
  not that its change was kept.
- **`:dao.data.atom/union`** is for values that are grow-only sets
  (including B-tree sets and datom sets). The value is the union of every
  head's value. Nothing is ever lost, and forks need no reaction at all,
  since union is a CRDT merge (`dao.stream.discovery.md`: datoms merge by
  union, convergent by construction).

A deref yields the observed heads, the winner (for `:linear`), and the
value together. The heads are the evidence of what the value was
computed from.

`add-watch` is not a callback. A watcher is an interpreter holding
cursors on its source pool, and each record it counts is the watch event.

## 4. Change without coordination

### 4.1 `swap!` on a linear atom

`swap!` keeps Clojure's semantics: the function runs on the swapper, and it
is re-run against the newer value when its change loses.

1. Deref to get the heads, the winner `w`, and `value`.
2. Compute `value' = (f value)` locally. For a B-tree value this produces a
   new root that shares every unchanged subtree with `value`.
3. Store `value'` through the DHT content handle. Stored segments land in
   the swapper's local store first and replicate to the `k` nearest peers
   on a best-effort basis. Segments shared with `value` cost nothing.
4. Deposit a record with `:prev` = all observed heads and `:root` = the
   address of `value'`. Because it names every head, it is also a join:
   the swapper's view had no fork after this record.
5. Keep observing. While the record is on the winning line (§3.5), the
   change is in the value. If a concurrent record the swapper had not
   observed takes the line instead, the swapper's record has lost: go to
   1. The new record names both heads, so it joins the fork and re-applies
   `f` on top of the winner.

Nothing tells the swapper it lost. It sees the fork in the medium and
reacts to it. That reaction is the retry, and it is the whole of the
coordination.

`reset!` is the same loop with `f` ignoring its argument.
`compare-and-set!` is one pass with no retry: the change stands or it does
not, and the caller learns which by observation.

No step waits. The loop is an interpreter over cursors, and in Yin code it
is written with `dao.await`'s `go`/`<!`.

### 4.2 Joins

A fork left alone is still well defined, because resolution picks a winner
anyway. Joins exist to keep the DAG narrow, so readers resolve fewer heads.
Any participant that observes several heads may deposit a join whose
`:prev` is those heads:

- for `:union`, with the union as its root;
- for `:linear`, with the winner's root. This changes no value and no
  winning line: the join extends the line through the winner, and the
  heads that lost stay off it. It makes the resolution explicit rather
  than altering it.

A join is always safe to deposit more than once, by anyone: two identical
joins have the same address (they have the same content), and two
different ones are simply two more heads that resolve the same way.

### 4.3 Several values, one change

Clojure's `dosync` over several refs has no counterpart across atoms. That
is deliberate: it is the feature whose distributed form sank Avout. Values
that must change together belong to one atom whose value is a map (or
B-tree) of the parts, and one record changes them together.

## 5. Values in the DHT

- **Encoding.** Values and records are encoded as canonical CBOR
  (`dao.jing.cbor.md`), so an address minted on one host is the address
  every host mints for the same content. Resolution compares record
  addresses, so this is what makes two hosts agree on a winner.
- **Small values** are one segment whose payload is the value.
- **Large collections** are `dao.data.btree` trees stored through its
  `IStorage` (`dao.data.btree.md` §5.1). The root segment is
  self-describing: a branch or leaf blob per §5.2. A `sorted-map` is a
  sorted set of `[k v]` entries under a comparator on `k`; a persistent
  hash map would need its own segment layout (Open Decision OD-4).
- **Availability.** Every author's own store holds every segment of every
  root it deposited, because DHT writes land locally first. The DHT's
  best-effort replication decides how many *other* copies exist. A reader
  that cannot fetch a root does not count its record (§3.4), which
  delays that reader's view but never makes it compute a different value
  from the same counted records.
- **Segment size.** The UDP DHT refuses any message over its 1200-byte
  datagram budget, and oversized segments stay local-only
  (`dao.jing.dht.md`, *Operational limits*). Until fragmentation exists,
  an atom's trees must use a branching factor small enough that every
  node fits, and a single-segment value over the budget is refused by
  the swapper before it deposits. The default branching factor of 512 is
  far over budget.

## 6. Invariants

| # | Invariant |
|---|---|
| A1 | A participant deposits records only in its own stream. No stream has two writers and no participant writes to, addresses, or waits on another |
| A2 | Records and values are immutable and content-addressed; nothing is ever updated in place |
| A3 | Resolution is a pure function of the counted records: readers that count the same records compute the same heads, winner, winning line, and value |
| A4 | A `:linear` record's root is the value its author computed on top of the parent the resolution rule picks among its `:prev`; a record whose root was computed on any other parent is a defect of its author |
| A5 | A record counts only once its `:prev` records and its whole root are held; an uncountable record is unobserved, not an error |
| A6 | Resolution strategies come from a closed registry named in the genesis record; no reader evaluates code taken from the medium |
| A7 | A deref yields the observed heads with the value; no API returns a value without the evidence it was resolved from |
| A8 | Nothing here mutates DaoJing directly or asks the DHT for anything but content by address |
| A9 | No operation waits; every outcome is data on a stream |

## 7. What stigmergy costs

The design gives **convergence**, not **linearizability**, and says so:

- **No finality.** A `:linear` winner can be overtaken when a concurrent
  record arrives late, for example from across a partition. A swapper
  that is still observing will see its change lose and re-apply it. A
  swapper that has gone away leaves its lost change as a trace nobody
  re-applies (OD-3).
- **Views can differ.** Readers with different source pools, or different
  progress through the same pools, see different values. That is the
  normal state of an interpreted medium, not a fault. The heads in every
  deref say exactly which view it is.
- **Anyone in a reader's pool can write.** Until ShiBi exists, the trust
  boundary is which streams the reader chooses to fold.

What it removes is the whole failure class of coordinated designs: there
is no owner to lose, no leader to elect, no lock to strand, and no
participant whose absence makes the atom read-only.

## 8. Relationship to `dao.space`

This is the coordination `dao.space` already uses: agents deposit claims in
their own streams and derive current state read-side. A `:union` atom whose
value is a datom set is, structurally, a `dao.space` database, and a
published manifest address plays the role of a root. This design does not
merge the two. It records that a later unification would be a renaming,
not a redesign, and that anything learned here (counting, segment-size
limits, GC roots) applies to published indexes too.

## 9. Explicitly absent

- Any owner, steward, leader, or privileged writer.
- Proposals and answers: no participant is addressed, and no one judges.
- Coordinated change across atoms (`dosync`).
- Consensus and finality.
- Exactly-once application; a lost change is re-applied by its swapper
  reacting to the fork, or not at all.
- Access control beyond the reader's choice of source pool.

## 10. Prerequisites and phasing

The DHT's current limits decide what can be built now:

| Limit (`dao.jing.dht.md`) | Effect on atoms |
|---|---|
| 1200-byte datagrams, no fragmentation | Small branching factor mandatory; single-segment values capped |
| No GC or pinning | Unbounded growth of superseded values (OD-5) |
| Dead peers stay in the lookup shortlist | Counting and cold reads can stall on timeouts |
| JVM only, no NAT traversal | Participants that fetch from the DHT must be JVM peers with direct UDP reachability |
| Replication blocks `put` | Each deposit pays up to `k` bounded replication calls |

Phases:

1. **Local.** Genesis and record vocabulary, counting, both resolutions,
   the swap loop, and joins, over `memory-log` streams and a `dao.jing.mem`
   store in one process. Section 6 becomes the test suite, with
   concurrent swappers interleaved deterministically.
2. **Remote streams.** Participants on other peers reached through
   `dao.stream.remote`, with values still in a shared local store.
3. **DHT values.** Values and records stored via
   `create-content-dht-udp`, B-trees at a budget-safe branching factor,
   and source pools found through discovery.
4. **Large values.** Default branching factor restored once DHT
   fragmentation lands.

## 11. Open decisions

**OD-1. Name and placement (decided).** The namespace is `dao.data.atom`,
beside `dao.data.btree`, and its keywords live under `:dao.data.atom/`.
`dao.data.md` places specific data structures in `dao.data.*`
sub-namespaces, and an atom is one: a persistent identity over immutable
values, as the B-tree is a persistent collection over immutable segments.
Placing it here also keeps it off DaoJing's side of the storage boundary,
so it does not give DaoJing a mutable root. `dao.jing.md` still holds:
DaoJing keeps no roots, CAS records, or deletes, and `dao.jing/get` still
rejects anything that is not a content address. `dao.data.atom` consumes
DaoJing's contract the way `dao.data.btree`'s `IStorage` does; it adds
nothing to it.

**OD-2. The resolution registry.** Whether `:linear` and `:union` are
enough, what else belongs (for example last-writer-wins maps keyed per
entry), and whether the depth-then-address rule is the right winner rule.
Depth favours whoever has built on the most history; the address
tiebreak is arbitrary but agreed.

**OD-3. Changes as data.** A record could carry its change as a Yin
continuation alongside its root. Any participant that saw it lose could
then re-apply it on the winner, so a lost change would no longer depend on
its author being alive. This executes code from the medium, so it needs
ShiBi metering and moves A6's boundary.

**OD-4. Hash maps.** Whether unsorted maps get a HAMT-over-segments layout or
are always represented as sorted B-trees.

**OD-5. Retention and GC.** An atom's live roots are the heads, but no single
peer knows every head, because no one sees every stream. Marking from the
heads a peer knows with `walk-addresses` is well defined for that peer's
own store; sweeping a shared DHT is not, because the DHT has no pinning or
eviction policy (`dao.jing.dht.md`, *Storage economics / GC*). Until one
exists, every superseded value accumulates.

**OD-6. Finality by convention.** An application that needs "this change
is final" can build it above this design as more traces: for example,
observers deposit attestations naming a record, and the application treats
a record as final once enough attestations from its chosen sources exist.
That is a quorum, chosen per application. It is not part of this design.

## 12. Lineage

- **Clojure atoms** supply the semantics: one identity, immutable values,
  `swap!` as compare-and-set with a local retry.
- **Stigmergy and the tuple space** (blackboard → Linda → `dao.space`)
  supply the coordination: participants act on traces in a shared medium
  and never on each other.
- **Datomic** supplies immutable segments in storage and peers that read
  and cache directly.
- **Git** supplies the history shape: a Merkle DAG of commits, where a
  fork is two children of one parent and a merge commit joins them.
- **CRDTs** supply `:union`: a merge that converges with no coordination.
- **Plan 9's Venti** supplies content-addressed archival blocks. Here
  there is not even Fossil's mutable root pointer, only records.
- **Avout** is the negative example (§2).
