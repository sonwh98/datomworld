# dao.data.btree — Universal Persistent B-Tree

Status: Proposed design plan, revised after a second review round. This
document details the
architectural design and phased execution plan for a unified, cross-platform
persistent B-tree implementation (`dao.data.btree`) that compiles from one
`.cljc` source on Clojure (JVM), ClojureScript (JS/V8), and ClojureDart
(Dart/Flutter). Node layout, settings, and protocol surface deliberately
mirror `persistent-sorted-set` 0.3.0 ("psset") — verified against the jar —
so that segments written by psset remain readable and mutation-invariant
compatible (§5.2).

**Related documents:**
- [dao.space.index.md](dao.space.index.md) — write-side index builder; owns
  every psset touchpoint today and is the future consumer of this library
- [dao.space.query.md](dao.space.query.md) — read-side query engine (Peer),
  lazy segment restoration, and Ruling 1 (no new storage protocol)
- [dao.jing.md](dao.jing.md) — storage boundary (segment KV store)

---

## 1. Background & Motivation

The query engine and index transactor rely on Nikita Prokopov's (tonsky)
`persistent-sorted-set/persistent-sorted-set` 0.3.0 for sorted indexes,
slicing, and lazy segment loading. All psset touchpoints live in
`dao.space.index` (`src/cljc/dao/space/index.cljc`); `dao.space.query` is
deliberately psset-free. The actual gap matrix, verified against the 0.3.0
jar (which ships `me/tonsky/persistent_sorted_set.cljs`, a complete native
ClojureScript B-tree with `sorted-set-by`/`from-sequential`/`slice`/`rslice`/
`seek`/`conj`/`disj`):

```
              in-memory structure + O(log n) slice/seek   durability (IStorage/store/restore)
  JVM         yes (Java host classes)                     yes
  CLJS        yes (native cljs implementation)            no
  CLJD        no (falls back to sorted-set-by)            no
```

1. **ClojureDart (`cljd`): no tree at all.** cljd builds fall back to
   `clojure.core/sorted-set-by`, a persistent red-black tree. That fallback
   does support log-n range seeks (`subseq`/`rsubseq`), but it lacks (a)
   psset's explicit-comparator `slice` API that `subseq-from` builds on —
   the current cljd fallback in `subseq-from` is a linear `drop-while` —
   and (b) any durability API, so published `{:indexes ...}` roots are read
   by eagerly walking the entire node graph (`walk-index-datoms`).
2. **ClojureScript (`cljs`): no durability.** The cljs tree is complete
   in-memory; missing are `IStorage`, `store`, `restore-by`, and
   `walk-addresses` (psset's README scopes durability to the Clojure
   version). Independently, JS/Web I/O is asynchronous (IndexedDB, network)
   and cannot block mid-traversal the way a synchronous node fault
   requires.
3. **JVM-only host classes.** psset's JVM speed comes from Java classes
   (`Branch.java`, `Leaf.java`, `Settings.java`) behind a Java `IStorage`
   interface, and `kv-storage` in `index.cljc` imports and constructs them
   directly.

### Why a full rewrite, given the cljs tree exists

Alternatives considered:

- **A cljs blob serializer over psset.cljs** (walk the existing cljs BTSet,
  emit §5.2 blobs). Cheap, and viable as an *interim* for cljs publishing.
  Rejected as the target: it gives no lazy restore on cljs, and leaves two
  tree implementations writing one durable format, so fill invariants (§5.2)
  can drift between writers.
- **Fork psset.cljs to add `IStorage`.** Lazy restore requires fault hooks
  *inside* the BTSet/Branch internals (`child(idx)` must call storage); that
  is a fork of the same size as writing fresh, and it still leaves cljd
  unserved.
- **Full `.cljc` rewrite (chosen).** cljd needs a tree regardless; one
  implementation then covers all three hosts, one invariant suite covers
  every writer and reader of the format, and durability is designed in
  rather than retrofitted. The node layout mirrors psset's exactly (§3.1)
  so the rewrite cost is mostly transcription, not invention.

### Why `dao.data.*`

The B-tree is a generic persistent sorted-set data structure. It knows
nothing about datoms; comparators are caller-supplied. `dao.space.index`
keeps owning the datom sort orders, the manifest, and the root conventions,
becoming a thin adapter over the generic structure. `dao.data.*` is the home
for such generic structures (first consumer: `dao.space.index`; future
consumer: Yin.VM).

---

## 2. Design Goals

- **Cross-platform parity:** one `.cljc` source compiling on Clojure (JVM),
  ClojureScript (JS/V8), and ClojureDart (Dart/Flutter), with the test suite
  running on all three hosts from Phase 1 — never "JVM first, port later."
- **Immutable & persistent:** structural sharing via path copying; every
  `conj`/`disj` returns a new root while sharing unmodified subtrees.
- **Tunable branching factor:** max keys per node N, 16–32 for lightweight
  test trees, up to 512 for Datomic-style fat segments; default 512,
  matching psset (`Settings.java`) and today's `publish-index!`.
- **Logarithmic seek:** `slice`/`rslice`/`subseq`/`rsubseq` descend in
  O(log n); extracting k results costs O(log n + k). On a lazily restored
  tree a seek loads only the descent path plus the traversed range.
- **Unified lazy durability (`IStorage`):** maps B-tree nodes to
  content-addressed segments in `dao.jing`'s key-value store, deserializing
  a node only when traversal reaches it. Built entirely on the existing
  `IKVStore` methods (Ruling 1, `dao.space.query.md`: no new storage
  protocol).
- **Bounded fault cache:** faulted children are held through
  host-appropriate references (§5.3), so a long-lived Peer over a large
  index does not retain every node it has ever touched.

---

## 3. Implementation Architecture

### 3.1 Node Definitions & Types

The tree is a B+ tree in the narrow sense — separators and child pointers in
branches, values in leaves only — **without leaf sibling links** (psset's
deliberate choice; iteration uses a path-stack Seq/Iter). Sibling pointers
are rejected: a leaf's next-pointer changes on every neighbor insert, which
would break structural sharing by propagating path copying across the whole
leaf level.

Node layout mirrors psset's (`ANode`/`Branch`/`Leaf`) field for field,
because the durability state machine depends on it:

```clojure
;; shared settings, one .cljc type used on all hosts
(deftype Settings [branching-factor   ;; max keys per node, default 512
                   ref-type           ;; :strong | :soft | :weak (§5.3), default :soft
                   edit])             ;; transient mutation lease (Phase 3):
                                      ;; host-appropriate mutable token
                                      ;; (JVM AtomicBoolean, cljs atom, cljd boxed mutable),
                                      ;; nil when persistent

;; JVM
(deftype Leaf   [^int len ^objects keys settings])
(deftype Branch [^int level ^int len ^objects keys
                 ^objects children ^objects addresses settings])

;; CLJS — keys/children/addresses are mutable js/Array
(deftype Leaf   [len keys settings])
(deftype Branch [level len keys children addresses settings])

;; CLJD — Dart's native array type is List
(deftype Leaf   [^int len ^List keys settings])
(deftype Branch [^int level ^int len ^List keys ^List children ^List addresses settings])
```

Field semantics (from `ANode.java`/`Branch.java`/`Settings.java`):

- `len` is the number of valid entries in `keys` (and in
  `children`/`addresses`); arrays are allocated at or above `len` and only
  partially filled. Under a transient edit lease, allocations grow by
  `expandLen` (psset: 8) at a time, so inserts do not reallocate an
  exact-sized array per operation.
- `minBranchingFactor` is `branching-factor >>> 1`; fill invariants and
  split/merge thresholds follow §5.2.
- Every node carries its `settings` (psset threads `_settings` through every
  node), so restore and mutation never need a side channel for it.
- `edit` is the transient lease and is **`nil` throughout Phases 1 and 2** —
  persistent nodes never read it. It is declared in the Phase 1 `Settings`
  shape on purpose: every node holds a `settings` reference, so adding the
  field later would touch every node construction site. Phase 3 fills it in
  without changing the type.

The `edit` token's host representation is chosen for the isolation check it
has to support, which is *not* the same problem on each host:

| host | token | staleness check |
|---|---|---|
| JVM | `AtomicBoolean` | `compareAndSet` — must be atomic; transients may be handed between threads even though concurrent use is illegal |
| CLJS | mutable boolean field on a `deftype` box | plain read; JS is single-threaded, so no atomicity is needed and a Clojure `atom` would be needless ceremony |
| CLJD | mutable boolean field on a `deftype` box | plain read; Dart isolates do not share mutable memory, so a transient cannot escape its isolate in the first place |

The rule is uniform even though the primitive is not: a transient owns its
lease, `persistent!` clears it, and any node operation that finds a cleared
lease throws. Only the JVM needs a CAS, because only the JVM can observe the
race.

State is tracked **per child slot**, not per branch. A `Branch` child slot
`i` is in exactly one of three states:

```
1. dirty (never stored / modified since restore):
     (addresses == nil || addresses[i] == nil) && children[i] == <node, strong>
2. stored (clean):
     addresses[i] == <segment-key> && children[i] == <ref-type reference to node>
3. stored, not yet faulted:
     addresses[i] == <segment-key> && (children == nil || children[i] == nil)
```

Transitions: `child(i)` dereferences state 2 via the settings' reference
type; on state 3 it calls `-restore` at `addresses[i]` and memoizes a
ref-type reference (state 2). Storing assigns `addresses[i]` and converts
an in-memory child from strong to ref-type. **Dirty is "no addresses"** —
`store-tree` (§5.1) stores exactly the dirty subgraph, children before
parents, and a freshly built in-memory tree is simply all-dirty. This makes
the lazy-read cache (a faulted child is memoized, never refetched within
its reference's lifetime) and the write path (path copying produces dirty
nodes with children and no addresses) both implementable.

**Mixed-state branches are valid, not an invariant violation.** The two
disjuncts in state 1 describe different situations — `addresses == nil` is a
freshly path-copied branch whose children are all dirty; `addresses[i] ==
nil` with sibling slots populated is a branch holding a mix of stored and
dirty children. The second arises by construction: a branch restored from
storage (all slots in state 2/3) that is then modified along one path has
exactly one dirty slot and the rest clean, and `store-tree` walking children
left to right passes through the same shape. psset permits this — `Branch`
assigns addresses per slot (`Branch.address(idx, address)`), never
whole-array — and so does this design. Two consequences to implement
deliberately:

- `store-tree` skips slots already carrying an address (that subtree is
  clean and content-addressed; re-storing it would be a no-op that rehashes
  the whole subgraph). This is what makes incremental re-publish cost
  proportional to the changed path rather than to the tree.
- A branch is only itself storable once **every** slot has an address, so
  `-store` on a branch is called after its children are done (the
  precondition stated in §5.1). No partially-addressed branch is ever
  serialized, so no blob can contain a nil address.

Crash safety follows from content addressing rather than from ordering: a
crash mid-`store-tree` leaves orphaned segments in storage and nothing else,
because the in-memory tree is process state and the root only advances by
`cas!` after storing completes (`publish-index!`). Those orphans are
unreferenced and harmless; reclaiming them is the segment-GC item in §7.

One protocol, with `settings` passed through mutation entry points (psset's
signatures):

```clojure
(defprotocol INode
  (node-lim-key       [node])
  (node-len           [node])
  (node-merge         [node next-node])
  (node-merge-n-split [node next-node])
  (node-lookup        [node cmp key])
  (node-conj          [node cmp key settings])
  (node-disj          [node cmp key root? left right settings]))
```

`node-merge-n-split` returns the three-node split result, as in psset.

### 3.2 Array Manipulation Layer (`arrays.cljc`)

The single backing-store abstraction. Node fields hold exactly these host
arrays — there is no `java.util.List` representation anywhere, so the node
types and the array layer cannot drift apart:

- **JVM:** `Object[]` + `System/arraycopy`.
- **ClojureScript:** `js/Array` (`slice`, `splice`, `concat`).
- **ClojureDart:** Dart `List` slicing and copying.

`arrays.cljc` is written first, and nodes are array-backed from day one.
There is no vector-backed prototype step; the core is not written three
times.

The psset `arrays.cljc` in the jar is nearly empty (`aclone`, `aconcat`,
`asort`) because the JVM implementation inlines the rest as Java. A `.cljc`
rewrite has to lift those inline operations into the layer. Derive the
surface from the call sites in `Leaf.add`/`Leaf.remove`/`Branch.add`/
`Branch.remove` (§3.3); it comes to roughly: `anew`, `alength`, `aget`,
`aset`, `aclone`, `acopy` (src, src-pos, dest, dest-pos, len — the
`System/arraycopy` shape, since node code copies sub-ranges constantly),
`asort`, `aconcat`, and the two composite helpers those methods use
repeatedly, `ainsert-at` and `aremove-at`.

### 3.3 Normative reference: the psset porting map

This document specifies **decisions, invariants, and the places this design
departs from psset**. It deliberately does not restate psset's B-tree
algorithms. For those, **psset 0.3.0's source is the normative reference**,
and the rewrite is a transcription against it (§1, "the rewrite cost is
mostly transcription, not invention").

The sources ship inside the dependency jar — no separate checkout:

```sh
unzip -o ~/.m2/repository/persistent-sorted-set/persistent-sorted-set/0.3.0/\
persistent-sorted-set-0.3.0.jar \
  'me/tonsky/persistent_sorted_set/*.java' \
  'me/tonsky/persistent_sorted_set.clj' \
  'me/tonsky/persistent_sorted_set.cljs' \
  'me/tonsky/persistent_sorted_set/arrays.cljc' -d psset-src/
```

`persistent_sorted_set.cljs` is a second, independent reference: it is the
same algorithms already expressed in ClojureScript, so where the Java is
awkward to read, the cljs version usually shows the shape a `.cljc` port
wants. It has no durability (§1), so it is a reference for §3–§4 only.

**Where this document and psset disagree, this document wins.** The
divergences are enumerated in §3.3.3; everything not listed there should
match psset's behavior.

#### 3.3.1 Mapping

| This design | psset source | Notes |
|---|---|---|
| `node-conj` | `Leaf.add` (`Leaf.java:37`), `Branch.add` (`Branch.java:156`) | return convention below |
| `node-disj` | `Leaf.remove` (`Leaf.java:97`), `Branch.remove` (`Branch.java:333`) | return convention below |
| `node-lookup` | `ANode.search` (`ANode.java:50`), `searchFirst` (`:72`), `searchLast` (`:85`) | binary search; negative result encodes the insertion point |
| `node-len` / `node-lim-key` | `ANode.len` (`:26`), `maxKey` (`:34`), `minKey` (`:30`) | |
| `node-merge` / `node-merge-n-split` | the join/borrow branches inside `Leaf.remove` / `Branch.remove` | not standalone methods in psset; extracted here |
| `child(i)` fault + memoize (§3.1 state 2/3) | `Branch.child(IStorage,int)` (`Branch.java:94`), `Branch.child(int,ANode)` (`:116`) | the ref-type deref lives here |
| address get/set (§3.1) | `Branch.address(int)` (`:69`), `Branch.address(int,Address)` (`:78`) | per-slot, never whole-array (§3.1) |
| `-store` / `store-tree` | `ANode.store` (`:121`), `Leaf.store` (`Leaf.java:228`), `Branch.store` (`Branch.java:614`), `PersistentSortedSet.store` (`:183`) | children before parents |
| `-restore` | `ANode.restore` (`ANode.java:98`), `restore-by` (`persistent_sorted_set.clj:156`) | but see §3.3.3 on the level argument |
| `walk-addresses` | `ANode.walkAddresses` (`:120`), `Branch` (`:599`), `Leaf` (`:223`), `PersistentSortedSet.walkAddresses` (`:162`) | |
| node `count` | `Branch.count` (`Branch.java:126`), `Leaf.count` (`Leaf.java:27`) | recursive; faults every node — see §3.3.3 |
| `slice` / `rslice` | `PersistentSortedSet.slice` (`:79`, `:84`), `rslice` (`:119`, `:123`); wrappers at `persistent_sorted_set.clj:26,36` | signature: `(slice set from to)` / `(slice set from to cmp)`; `to = nil` means unbounded above, which is how `subseq-from` calls it today |
| seq / iteration / `seek` | `Seq.java` (220 lines: `first` `:80`, `next` `:86`, `reduce` `:97`/`:109`, `chunkedFirst` `:125`, `rseq` `:152`, `seek` `:160`), plus `Chunk`, `Stitch`, `JavaIter` | the O(log n + k) guarantee (§2) lives here |
| `conj` / `disj` on the wrapper | `PersistentSortedSet.cons` (`:244`, `:248`), `disjoin` (`:279`, `:283`) | sentinel handling at `:251`, `:287`, `:290` |
| transient edit lease | `ANode.editable` (`ANode.java:46`), `Settings` `edit` | per-host token per §3.1 |
| bulk build | `from-sequential` (`persistent_sorted_set.clj:127`), split at `avg = (min+max)/2` (`:107-108`) | the fill policy §5.2 requires reproducing |

#### 3.3.2 Return conventions (easy to get wrong; state them in the protocol)

`add` and `remove` both return an array of nodes, using two shared empty-array
sentinels (`PersistentSortedSet.java:16-17`). These are identity-compared, not
value-compared — two distinct zero-length arrays:

```
node-conj ->
  UNCHANGED     ;; key already present, no change
  EARLY_EXIT    ;; transient edited in place, nothing to propagate upward
  [n]           ;; single replacement node (or `this` when only maxKey moved)
  [n1 n2]       ;; split

node-disj ->
  UNCHANGED     ;; key absent
  EARLY_EXIT    ;; transient edited in place
  [left center right]   ;; any element may be nil:
                        ;;   [nil join right]      joined into left neighbor
                        ;;   [left join nil]       joined into right neighbor
                        ;;   [newLeft newCenter right]  borrowed from left
```

A `.cljc` port needs two distinguishable sentinel values with identity
semantics on every host; do not model them as `nil` or as `[]`, because
`[]` would collide under value equality.

#### 3.3.3 Where this design deliberately differs

Everything below is a departure from psset and must **not** be transcribed:

| Area | psset | This design | Why |
|---|---|---|---|
| Storage interface | Java `IStorage` interface | Clojure `IStorage` protocol, `-store`/`-restore`/`-accessed` (§5.1) | no Java host classes; one `.cljc` source |
| Node classes | Java `Branch`/`Leaf`/`Settings` | `deftype` per host (§3.1) | same reason |
| `-restore` arity | `ANode.restore(level, keys, addresses, settings)` | no level argument; node type is read from blob shape (§5.1) | blobs are already self-describing; the level parameter invites a protocol that can disagree with its data |
| Blob integrity | none | rehash-and-compare on restore, `"corrupt index segment"` (§5.2) | the address already *is* the sha256; verification is free |
| Ref-type default | always `:soft` (`Settings.java`) | per host: `:soft` JVM, `:strong` cljs/cljd (§5.3) | `:soft` silently degrades to `:weak` off-JVM |
| Transients + lazy nodes | unspecified | modification path pinned strongly for the lease's lifetime (Phase 3) | a weakly-held child can refault as a different object mid-transient |
| Async backends | no concept | uniform API; `"unhydrated segment on write path"`; `hydrate!` public (§5.4) | Flutter Web / browser cljs have no sync backend |
| `count` after restore | `-1` sentinel, first `count()` walks and faults the whole tree | count carried from the manifest into `restore-tree` (§5.1) | an O(n) full-fault `count` would defeat §5.5 |
| CLJD collection surface | n/a | no Dart `Set` interface (§4) | contract too broad to honor on a deftype |

---

## 4. Host Collection Protocols

The `BTSet` wrapper (`{meta cmp root cnt storage settings}`, psset's shape —
`cnt` gives O(1) `count`, including on lazily restored trees, which is why
`restore-tree` takes the count from the manifest rather than deriving it,
§5.1) implements, per host:

- **JVM:** `IPersistentSet`, `Sorted` (including `seqFrom`), `Seqable`,
  `Counted`, `Reversible`, `IReduce` (early termination via `reduced`),
  `IObj`, `IFn` (set-as-function), `ILookup`, `Iterable`, `java.util.Set`,
  `java.io.Serializable`, `IEditableCollection`, and **`IHashEq`**.
  Equality/hashing follows `APersistentSortedSet`: `equiv` against any
  `java.util.Set`, and `hasheq` via `Murmur3/hashUnordered` (memoized).
  `IHashEq` is not optional: Clojure's `=` on collections and hash-map
  bucketing both go through `hasheq`, not `hashCode`, so without it the
  "(= btset (sorted-set ...)) holds and BTSet works as a map key" promise
  does not hold.
- **ClojureScript:** `ISet`, `ISorted`, `ISeqable`, `ICounted`,
  `IReversible`, `IReduce`, `IFn`, `ILookup`, `IHash` (cljs `hash` is
  hasheq), `IEquiv`, `IWithMeta`, `IEditableCollection`,
  `IPrintWithWriter`.
- **ClojureDart:** `IPersistentSet`, `ISeqable`, `ICounted`, `IReduce`,
  `IFn`, `ILookup`, `Iterable`, plus the equiv/hash contracts ClojureDart
  sets satisfy. No Dart `Set` interface: its contract is too broad to
  implement faithfully on a deftype, and nothing consumes it.

**Hash memoization.** The memo lives on the `BTSet` wrapper, never on a
node — nodes are shared between versions, so a node-level memo would be
wrong the moment a subtree is reused under a different root. Invalidation is
therefore structural rather than explicit: `conj`/`disj` return a new
`BTSet`, which starts with an empty memo slot; there is nothing to clear.
The set is immutable, so the memo is computed at most once per version and a
benign race (two threads computing the same value) is harmless.

Each host uses its own unordered-hash primitive and they are **not required
to agree across hosts** — hash values are in-process identity, never
serialized into segments or compared across platforms:

| host | primitive |
|---|---|
| JVM | `Murmur3/hashUnordered` (matches `APersistentSortedSet`) |
| CLJS | `cljs.core/hash-unordered-coll` |
| CLJD | ClojureDart's unordered-collection hash |

What *is* required on every host is the internal contract: equal sets hash
equal, and the hash agrees with the host's own `sorted-set` for the same
elements. Phase 2's conformance suite checks that per host.

### Slicing & Iteration Invariant

`slice`/`rslice` take an explicit comparator (psset-compatible signatures);
`subseq`/`rsubseq` delegate to them. JVM/CLJS expose path-stack seq views;
CLJD exposes a Dart `Iterator` that seeks logarithmically. A slice over a
lazily restored tree loads only the seek path plus the traversed range
(§5.5).

---

## 5. Durability

### 5.1 IStorage, specified

Modeled on psset's `IStorage.java` (`store`, `restore`, default no-op
`accessed`), over `IKVStore`:

```clojure
(defprotocol IStorage
  (-store   [storage node]
    "Serialize node, put! under jing/segment-key, return the address
     (a :segment/sha256-<hash> key). Called only after all of the node's
     children have been stored and have addresses.")
  (-restore [storage address]
    "get + deserialize the node at address. Node type is determined by the
     *shape of the retrieved blob*, not by the address: a map carrying
     :addresses is a Branch, one with :keys alone is a Leaf — the blobs are
     self-describing, exactly as kv-storage reads them today. The protocol
     therefore takes no level argument. Throws ex-info \"missing index
     segment\" on absence.")
  (-accessed [storage address]
    "Optional no-op hook, called when a memoized child is reused; lets a
     storage layer keep LRU bookkeeping for a cache of its own (see the
     layering note below)."))
```

Node IDs are content addresses: `-store` mints keys with `jing/segment-key`
over the EDN blob, exactly as `kv-storage` does today.

**Two caches, two layers — not two competing eviction policies.** `-accessed`
and the ref-types of §5.3 operate at different levels and must not be
conflated:

- The **faulted-child cache** is in the tree: `children[i]` memoizing a
  restored node, released by the garbage collector according to the
  configured ref-type. The tree never evicts deliberately; it only holds
  references of a chosen strength.
- The **segment cache** is in the storage layer, below `-restore` — for
  example a `KVMem` hydration cache in front of an async backend (§5.4).
  `-accessed` exists so that layer can maintain recency without the tree
  knowing about it.

The tree's cache is authoritative for correctness (a memoized child must
stay valid while referenced); the storage cache is pure acceleration and may
drop anything, since `-restore` can always refetch. An implementation that
made `-accessed` evict tree-held children would be wrong.

Entry points:

```clojure
(store-tree set storage)            ;; store the dirty subgraph, children before parents
                                    ;; (Merkle by construction) -> root address
(restore-tree cmp address storage cnt)  ;; a lazy BTSet; nothing fetched until traversed.
                                    ;; `cnt` is the element count from the manifest — see below
(walk-addresses storage address)    ;; every segment key reachable from address —
                                    ;; the mark hook for segment GC
```

**`restore-tree` takes the count, unlike psset — and that is deliberate.**
psset constructs a restored set with `-1` for count
(`persistent_sorted_set.clj:163`) and computes it on demand:

```java
public int count() {                                  // PersistentSortedSet.java:213
  if (_count < 0) _count = root().count(_storage);    // recursive; faults EVERY node
  return _count;
}
```

`alterCount` propagates the sentinel (`:65`), so `conj`/`disj` never repair
it. The consequence is that a single `count` call on a lazily restored set
walks the whole tree and pulls every segment — silently defeating the
laziness contract of §5.5, which exists precisely to keep a bound-`e` match
under 6 fetches. Under §4's O(1) `count` promise that trap is invisible at
the call site.

The manifest already carries `:count n` (§5.2), so this design threads it
through instead: `restore-tree` requires the count, `BTSet.cnt` is populated
from it, and O(1) `count` holds for restored trees as it does for in-memory
ones. Two obligations follow:

- `dao.space.index` passes `(:count manifest)` when restoring; a manifest
  without `:count` is from a pre-`{:indexes ...}` root shape and is read by
  the eager path (`walk-index-datoms`), not `restore-tree`.
- Node-level recursive `count` (`Branch.count`, `Leaf.count`) is still needed
  for in-memory trees, but **must never be reachable from `BTSet.count`** on
  a restored tree. Phase 4 asserts this: `count` on a restored set performs
  zero segment fetches.

All three are new code here, but none is a new *idea*: psset ships the same
three (`store`, `restore-by`, and `walk-addresses`, whose docstring is
"Visit each address used by this set. Usable for cleaning up garbage left in
storage from previous versions of the set"). `walk-addresses` is a plain
reachability traversal over the node graph — it returns segment keys and
takes no position on when or whether a collector runs, which is why §7 still
carries segment GC as an open item.

### 5.2 On-disk format: byte-compatible with what `publish-index!` writes today

- Leaf: `{:keys [...]}`; Branch: `{:level n :keys [...] :addresses [...]}` —
  plain EDN v-maps, unchanged. Every segment already published remains
  readable, and `walk-index-datoms` keeps working unmodified.
- Empty index: root address `nil`; walk of nil ⇒ (). Existing convention,
  kept.
- Manifest: `{:indexes {:eavt <addr> :aevt <addr> :avet <addr> :vaet <addr>}
  :count n}` stays valid. New publishes add an optional, additive
  `:branching-factor n` at the top level; absent means 512 (today's
  behavior, where restore uses default settings). Old readers ignore it.
- Comparator identity: the index name in `:indexes` is the comparator key.
  Restore resolves comparators through a closed map `{:eavt eavt-cmp :aevt
  aevt-cmp :avet avet-cmp :vaet vaet-cmp}` owned by `dao.space.index` —
  never `resolve`/`ns-resolve` on wire data.

**Integrity comes free from content addressing.** A segment's key *is* the
sha256 of its blob (`jing/segment-key`), so `-restore` can rehash what it
retrieved and compare against the address it asked for. That detects
truncation, partial writes, and backend corruption for the cost of one hash,
with no extra metadata and no format change. The distinction it draws is
worth keeping separate in the error surface:

- absent blob → `ex-info "missing index segment"` (today's behavior)
- present but hash-mismatched → `ex-info "corrupt index segment"`, carrying
  the expected and actual addresses

Verification is on by default and switchable per storage instance, since on
a trusted in-process `KVMem` it is pure overhead while on a networked DHT it
is the only thing standing between a bad peer and a silently wrong query.

**Byte compatibility is necessary but not sufficient.** Reading
psset-written segments yields nodes whose fill levels follow psset's split
policy (`from-sorted-array` chunks at `avg = (min + max) / 2`, min fill
`branching-factor >>> 1`, underflow merge/borrow in
`persistent_sorted_set.clj`). Because §5.3/§5.4 allow `conj`/`disj` onto
restored sets, this implementation's split/merge invariants must reproduce
psset's exactly, or mutation on a restored tree could produce malformed
nodes.

This is pinned by fixtures, and their **provenance is constrained by the
platform matrix**: psset durability is JVM-only and psset has no cljd build
at all, so fixtures cannot be generated at test time on cljs or cljd — and
Phase 4 drops the psset dependency entirely, so they cannot be generated at
test time on the JVM either once that lands. Therefore:

- Fixtures are **static EDN blobs checked into the repository**, generated
  once by psset at several branching factors (at minimum 32 and 512) by a
  committed generator script, with the psset version recorded alongside.
- The same files are read by all three hosts, which is what makes the
  restored-tree mutation runs identical across the Phase 1 test pass.
- Regeneration is a deliberate, reviewed act — the blobs encode the format
  compatibility this design promises, so a diff in them is a compatibility
  break and should read like one in review.

### 5.3 Faulted-child cache & eviction

Without eviction, `restore-tree`'s "nothing fetched until traversed"
implies every faulted node is retained forever — an unbounded leak for a
long-lived Peer over a large index. psset bounds this with `RefType
{STRONG, SOFT, WEAK}` and `Settings/makeReference`; this design keeps the
mechanism and states the cross-platform degradation explicitly, because it
is the one place "one .cljc source" meets a genuine host capability gap:

- **JVM:** `:strong` → plain reference (no eviction), `:soft` →
  `SoftReference`, `:weak` → `WeakReference`. Default `:soft`, matching
  `Settings.java`.
- **CLJS:** `js/WeakRef` (V8/modern browsers) for `:weak`; **`:soft`
  degrades to `:weak`** — JS has no memory-pressure-aware soft reference.
- **CLJD:** `WeakReference` (dart:core) for `:weak`; **`:soft` degrades to
  `:weak`** for the same reason.

Consequences, stated rather than hidden: under `:weak` (or degraded
`:soft`), a faulted child may be collected between slices and silently
refetched — correct, but refetch-heavy under memory pressure; `:strong`
pins the fault cache, bounded only by the working set actually touched.

**The default is therefore per host, not global.** A single `:soft` default
would resolve to `:weak` on cljs and cljd, i.e. the *most* refetch-prone
option on exactly the two platforms with the least headroom to absorb it,
and it would do so silently. Instead:

| host | default | rationale |
|---|---|---|
| JVM | `:soft` | a real memory-pressure-aware reference: caches until the heap needs the space. Matches `Settings.java`. |
| CLJS | `:strong` | no soft equivalent; `:weak` can drop a child between two slices of one query. Bound by the working set actually touched, which for a seek is O(log n) nodes. |
| CLJD | `:strong` | same reasoning; Flutter's GC is generational and would collect weak children aggressively between frames. |

`:weak` stays available on every host for long-lived readers over indexes
too large to pin, and `:strong` is available on the JVM for latency-critical
paths. What is no longer available is a default that degrades into the worst
case without saying so.

The residual question is not which default to ship but whether `:strong`
needs a **bound** — a working set that is O(log n) per seek is fine, while a
full scan under `:strong` pins the whole index. An explicit LRU over the
fault cache (using `-accessed`, §5.1) is the answer if measurement shows it
is needed; that measurement is the §7 item.

### 5.4 The async gap, composed

Which backends are actually synchronous, per platform (verified against
`dao/jing/mem.cljc` and `dao/jing/file.cljc`):

```
backend           JVM    cljs/Node   cljs/browser   cljd desktop/mobile   cljd Flutter Web
KVMem             sync   sync        sync           sync                  sync
KVFile            sync   sync (fs)   n/a            sync (dart:io sync)   n/a (no dart:io)
dao.jing.remote   async  async       async          async                 async
DHT (networked)   async  async       async          async                 async
IndexedDB         n/a    n/a         async          n/a                   n/a
```

So the "sync backend" default holds for local storage on JVM, Node, and
desktop/mobile Dart (`file.cljc` uses `dart:io` sync APIs), and for
in-memory stores everywhere — but **Flutter Web has no `KVFile` at all**,
and browser cljs has only async IndexedDB, so those targets are async-only
by construction. Selection rule:

1. **Sync backend (the common case above):** sync `IStorage` directly over
   the store. No special casing.
2. **Async backend, sync consumer (e.g. Datalog `q`):** hydrate-then-query.
   An async pre-pass fetches nodes into a `KVMem` cache, then the sync path
   runs over that cache. Two granularities: full-graph (equivalent to
   today's `walk-index-datoms`; correct and simple) or path-precise (an
   iterative descent — fetch a level, choose the child, fetch the next —
   costing O(log n) sequential round-trips per seek, chosen when the graph
   is large). This is strategy 1 with an async loader in front, not a
   separate mechanism.
3. **Async backend, async consumer:** an optional `slice-async`/`get-async`
   returning a Promise (cljs) / Completer-backed Future (cljd), using the
   non-blocking reschedule pattern from `yin/repl.cljc` (Promise/Completer +
   setTimeout/Future.delayed). No core.async dependency.

Two platform notes: sync file I/O on Flutter mobile runs on the calling
thread, so apps that need queries off the UI thread use rule 2 with a
worker; and rule 1 on Flutter Web is vacuous — there is no local file
backend to be sync over.

**Write path under laziness:** `conj`/`disj` on a lazily restored set forces
synchronous loads along the modification path, and `store-tree` re-stores
the dirty nodes. The incremental-indexing open item in `dao.space.index.md`
(conj onto restored sets plus re-store of the changed path) inherits the
same constraint: on async backends it requires prior hydration.

#### The API is uniform; hydration state, not platform, decides what succeeds

That constraint has a consequence sharp enough to name, because it is the
one place this design does not achieve parity and pretending otherwise would
mislead an implementer. On Flutter Web and browser cljs there is no
synchronous backend at all (table above), so a mutation that needs to fault
a node cannot block to fetch it. Two ways to express that, and the choice
matters:

- **(a) Uniform API, explicit failure.** `conj`/`disj` exist everywhere with
  the same signature. On a lazily restored tree whose modification path is
  not resident, they throw
  `ex-info "unhydrated segment on write path" {:address addr}`.
- **(b) Platform-varying API.** `conj`/`disj` are simply absent on
  async-only targets.

**This design takes (a).** The API surface stays uniform across hosts — one
`.cljc` source that presents one set of operations is the whole point — and
the failure is a runtime condition tied to *hydration state*, not to a
compile target. The same call succeeds on browser cljs after a hydration
pre-pass and fails without one, exactly as it would on the JVM against a
store whose segments had been deleted. Platform determines only how easy it
is to be hydrated, not what operations exist.

Two obligations follow. First, hydration must be requestable rather than
incidental: `hydrate!`/`hydrate-async` (strategy 2 above) is public API, not
an internal detail of `q`, so a caller that intends to mutate can guarantee
residency first. Second, a fully in-memory tree — one built by `conj` and
never restored — has no unfaulted nodes by construction and therefore
mutates freely on every platform, including Flutter Web. The restriction is
specifically about mutating *lazily restored* trees on async-only backends,
and that narrower statement is what the tests should assert.

### 5.5 Laziness contract (the regression bar)

The existing `publish-index-lazy-fetch` measurement — a bound-e match over
600 datoms at branching factor 32 stays under 6 segment fetches while the
tree exceeds 15 segments (`index_test.cljc`) — is ported verbatim into the
new test suite. The §3.1 child-memoization design is what makes this bar
reachable: a faulted child is cached in its branch slot, never refetched
within its reference's lifetime. This is the behavior the rewrite exists to
keep.

---

## 6. Phased Execution Plan

Phases land in dependency order. Every phase ships with its tests green on
all three hosts:

```
clj -M:test                                                          (JVM)
clj -M:cljs -m shadow.cljs.devtools.cli compile test && node target/node-tests.js
clj -M:cljd test                                                     (Dart; requires flutter on PATH, e.g. via mise exec)
```

### Phase 1: Core tree, all platforms

- **Start by extracting the psset sources (§3.3) — the algorithms are ported
  from them, not invented here.** Work the §3.3.1 mapping row by row, and
  check every row against the §3.3.3 divergence table before transcribing.
- `arrays.cljc` (surface derived per §3.2 from the node call sites);
  `Settings`; node split/merge/borrow with psset's exact fill invariants
  (§5.2); `BTSet` with `conj`/`disj`/`lookup`/`seq`/`slice`, array-backed
  from day one. The two `add`/`remove` sentinels get identity semantics on
  every host (§3.3.2).
- Generative invariant tests (height balance, element bounds, count, order)
  over random insert/delete runs, on JVM, Node cljs, and cljd in the same
  test pass. Run size is per-host — up to 100,000 items on JVM, a smaller
  fixed bound (e.g. 10,000) on Node and cljd so the flutter compile/run
  cycle stays usable — with identical invariants and generators.
- psset fixture pins: segments generated by psset at several branching
  factors are restored, mutated (`conj`/`disj`), and re-validated (§5.2).
- Preflight: verify test.check runs under ClojureDart. If it does not, fall
  back to hand-rolled seeded generation — no third-party test dependency
  that lacks a cljd port.

### Phase 2: Collection protocol conformance

- The full §4 protocol matrix on all three hosts, including `IFn`,
  `ILookup`, and `IHashEq`/`hashUnordered` on JVM.
- A shared conformance suite: `conj`/`disj`/`get`/`contains?`/`seq`/`rseq`,
  set-as-function invocation, `reduce` with early `reduced`, O(1) `count`,
  equality and `hasheq` against `sorted-set` fixtures (including BTSet as a
  hash-map key), `subseq`/`rsubseq` compatibility, `slice`/`rslice` with
  explicit comparators, metadata round-trip.

### Phase 3: Transients & performance

- Edit-lease mutation through `settings` (`edit` token, per-host
  representation per §3.1); growable allocations via `expandLen`;
  `IEditableCollection`/`ITransientSet` semantics with isolation (use of a
  stale transient throws).
- **Transients over a lazily restored tree pin their path.** The edit lease
  identifies nodes it is allowed to mutate in place; a faulted child held
  only by a `:weak` (or degraded `:soft`) reference can be collected
  mid-transient and refaulted as a *different object*, which silently breaks
  that identity and would mutate a node the lease does not own. A transient
  therefore holds strong references to every node on its modification path
  for the lifetime of the lease, regardless of the configured ref-type,
  releasing them at `persistent!`. This interaction is why §5.3's ref-type
  is a property of the read cache only.
- A benchmark suite with a gate **per host**, since parity is goal #1:
  - **JVM:** within the agreed factor of psset (Java host classes) on bulk
    build, incremental conj, point lookup, slice extraction.
  - **CLJS:** within the agreed factor of psset.cljs — a complete native
    baseline exists; use it.
  - **CLJD:** no psset baseline; must beat the current fallback
    (`sorted-set-by` + `subseq`) on seek/slice workloads and stay above an
    absolute ops/sec floor recorded at first measurement. A cljd tree 50x
    slower than the other hosts fails this gate.
- `dao.space.index` does not switch over until all three gates pass.

### Phase 4: Durability & integration

- `IStorage` over `IKVStore`; `store-tree`/`restore-tree`/`walk-addresses`;
  byte-compatible blobs plus the additive `:branching-factor`; closed-map
  comparator resolution (§5.2); ref-type eviction per §5.3.
- Tests: the §5.5 lazy-fetch counting suite, durability round-trips in the
  style of `index-root-readable-from-plain-node-blobs` (a hand-built node
  graph, no library involvement, readable on every platform), an eviction
  suite (fault, release, refault under `:weak`), a corruption suite (mutate
  a stored blob, assert `-restore` raises "corrupt index segment" on the
  hash mismatch, §5.2), and mixed-state store coverage (restore a tree,
  modify one path, `store-tree`, assert only the dirty subgraph is written
  and clean siblings keep their original addresses, §3.1).
- Write-path residency: assert `conj` on an unhydrated lazily restored tree
  raises `"unhydrated segment on write path"`, and succeeds after
  `hydrate!` (§5.4) — the uniform-API decision, made testable.
- `count` on a lazily restored tree performs **zero** segment fetches and
  agrees with the manifest's `:count` (§5.1) — the regression guard against
  reintroducing psset's fault-the-whole-tree `count`.
- Switch `dao.space.index`'s five psset touchpoints (`sorted-index-by`,
  `subseq-from`, `kv-storage`, `restored-indexes`, `publish-index!`) to
  `dao.data.btree` and drop the psset dependency. `dao.space.query` remains
  btree-free; `walk-index-datoms` stays as the universal eager path.

### Dependency picture after Phase 4

```
dao.space.stream ──► dao.space.index ──requires──► dao.data.btree
 (write path)       (datom sort orders,             (generic persistent
                     manifest, root conventions;     sorted set; knows
                     thin adapter)                   nothing of datoms)
                          │                              │
                          ▼                              ▼
                     dao.jing (IKVStore) ◄── IStorage over put!/get
                          │
                          ▼
                     dao.space.query (unchanged; btree-free)
```

---

## 7. Open Items

Carried over from `dao.space.index.md`, plus one this design adds:

- **Segment GC** — superseded index segments accumulate forever, and a
  crash mid-`store-tree` adds unreferenced orphans (§3.1). `walk-addresses`
  (§5.1) is the mark hook; the sweep half, and when it is safe to run
  against a live store, is unspecified.
- **Incremental indexing** — conj onto restored sets plus re-store of the
  changed path; inherits the hydration constraint recorded in §5.4.
- **Fault-cache bounding** — the per-host ref-type defaults are settled
  (§5.3: `:soft` on JVM, `:strong` off it). What remains is measurement:
  whether `:strong` needs an explicit LRU bound (via `-accessed`, §5.1) for
  workloads that scan rather than seek, where the pinned working set is the
  whole index rather than O(log n) nodes.
- **Hydration granularity for async-only targets** — §5.4 settles the API
  question (uniform surface, residency-dependent failure) and offers
  full-graph vs path-precise hydration, but not when to choose which. The
  full-graph pre-pass loads an entire index into memory before the first
  query, which on a memory-constrained Flutter Web client is exactly where
  it is least affordable; path-precise costs O(log n) sequential round
  trips per seek instead. The crossover is a per-backend measurement.
- **K-way merge of lazy indexes** — a federated query spans several stream
  roots, and answering it in index order means merging N sorted `{:indexes
  ...}` trees at once. Doing that lazily requires advancing N cursors in
  lockstep while faulting from N segment graphs; today the federated path
  sidesteps it by walking every tree eagerly (`walk-index-datoms`). Deferred
  because it costs laziness only on federated reads, which are the rarer
  case.
