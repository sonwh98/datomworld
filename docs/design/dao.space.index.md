# dao.space.index — The Transactor-Side Indexing Library

Status: implemented. The mechanism (owner-built, content-addressed B-Tree
segments) shipped 2026-07-10 and runs today on `dao.data.btree`; this document
records why the library exists, what it owns, its public surface, and the
boundary between it and the query library. The executable contract is
`test/dao/space/index_test.cljc`.

**Related documents:**
- `docs/design/dao.space.query.md` — the reader-side consumer of the
  realization this library owns
- `docs/design/dao.space.md` — the tuple space; *Three Boundaries* maps
  Transactor/Storage/Query onto streams
- `docs/design/dao.jing.md` — the storage boundary; publication through an
  explicit intake pool
- `docs/design/dao.jing.dht.md` — the distributed backend the published blobs
  can be stored into
- `docs/design/dao.data.btree.md` — the cross-platform tree and its storage
  adapter
- `docs/datomic.md` — the architecture the analogy below leans on

## Why a separate library

In Datomic, the **transactor** builds the covered indexes and saves them to
storage; **peers** pull segments and answer queries. datom.world decentralizes
the transactor: every agent appending to its own `dao.stream` is its own
transactor (`dao.space.md`, *Three Boundaries*) — so every agent also owns the
transactor's other duty, **indexing its own datoms**. That duty needs a
library the same way querying does:

- `dao.space.index` — what a stream owner runs: snapshot the local stream,
  build the covered indexes, and enqueue them as immutable content-addressed
  segments plus a manifest through a DaoJing intake stream. The write-side
  counterpart of the pair.
- `dao.space.query` — the embeddable Peer: open bounded source descriptors,
  match, run Datalog, and pull. The covered-index DaoStream adapter belongs to
  `dao.space.index`; query consumes only its logical d5 elements.

The boundary is strict: **query never writes; index owns the realization both
sides share.** It is the same move Datomic makes between transactor and peer —
one party materializes, many parties consume — except here "the transactor"
is not a process but a library duty every agent carries.

## What the library owns

One namespace, `src/cljc/dao/space/index.cljc`. Everything below is the index
*realization*: the shared vocabulary a builder and a reader must agree on.

- **Sort orders** — `eavt-cmp` / `aevt-cmp` / `avet-cmp` / `vaet-cmp` over
  heterogeneous values (`compare-vals`: type-ranked, nil-first). The
  comparators also serve query-only db-values, whose ids may be caller-chosen;
  persisted local d5 datoms are stricter: non-negative integer e/t, integer
  m, and namespaced keyword a. The library also owns the datom slot accessors
  (`datom-e/a/v/t/m`) they read through. Covered indexes contain canonical
  local d5 datoms only. Source scope belongs to the query interpreter and is
  not appended to an indexed tuple.
- **The in-memory index value** — `index-datoms` builds
  `{:eavt :aevt :avet :vaet}` `dao.data.btree` sorted sets. `subseq-from`
  delegates to `dao.data.btree/slice`: a log-n descent that, on a restored
  tree, loads only the seek path plus the matching range. The implementation
  is the same on JVM, ClojureScript, and ClojureDart.
- **The snapshot** — `snapshot-datoms` reads an agent-local stream from
  cursor zero with `ds/next`. A stream element is either one canonical datom
  vector `[e a v t m]` or one atomic transaction record
  `{:dao.space/transaction {:t n :datoms [...]}}`; transaction records are
  validated and flattened into their datoms. `:blocked` and `:end` finish the
  snapshot at the current tail; `:daostream/gap` and malformed stream results,
  datoms, or transaction records throw. Because the snapshot starts at
  position zero, the local stream must retain its complete history — a
  retention gap aborts publication before anything is emitted.
- **The persisted node-blob format, both directions** — a
  `dao.data.btree/IStorage` adapter over a DaoJing content-store handle
  (`dao.data.btree.storage/kv-storage`): nodes store as plain-EDN
  content-addressed segment blobs (leaf `{:keys [...]}`, branch
  `{:level n :keys [...] :addresses [...]}`), addresses minted by
  `dao.jing/materialize!` — Merkle by construction, since `dao.data.btree`
  stores children before parents. `read-manifest` validates a manifest and its
  content address; `read-datoms` walks the EAVT node graph eagerly using only
  `jing/get`; `restored-indexes` re-attaches a published manifest's trees
  lazily on every platform.
- **The transportable read coordinate** — `published-index` constructs a
  serializable exact-bounded descriptor from a DaoJing coordinate and an
  immutable manifest address. Opening it validates the descriptor, opens the
  coordinate, eagerly walks EAVT into canonical d5, and returns a read-only
  closed realization. Physical B-tree nodes never cross the stream boundary.
- **The transactor entry point** — `publish-index!`: snapshot the stream,
  build the four covered indexes, append the node blobs and the manifest to
  one intake stream selected from an explicit pool. DaoJing itself is never
  mutated directly.

## The manifest

The published manifest is exactly

```clojure
{:indexes {:eavt <segment-address-or-nil>
           :aevt <segment-address-or-nil>
           :avet <segment-address-or-nil>
           :vaet <segment-address-or-nil>}
 :count n
 :branching-factor n}
```

with no source stream, no pool, no epoch, and no self-address. `read-manifest`
accepts only this shape: the four index keys, a non-negative integer `:count`,
an integer `:branching-factor` of at least two, index addresses all nil when
the count is zero and all `:segment/sha256-...` addresses otherwise, and the
manifest's content address matching the requested address. The manifest
address is derived from the manifest alone and never depends on which intake
stream carried it. `:count` is the cardinality of the covered index set, not
the number of duplicate occurrences in the source stream; all four restored
trees therefore report the same O(1) count they actually contain.

## Public surface

```clojure
(require '[dao.space.index :as index])

;; the transactor's move (JVM, ClojureScript, and ClojureDart)
(index/publish-index! local-stream intake-pool)
(index/publish-index! local-stream intake-pool
                      {:branching-factor 512})    ; max keys per node,
                                                  ; Datomic-style fat segments
(index/publish-index! local-stream intake-pool
                      {:select-stream f})         ; f receives the pool and
                                                  ; returns one intake stream
;; => {:manifest-address :segment/sha256-… :manifest {:indexes {...} :count n :branching-factor n}}

;; the snapshot (reads and validates a local stream)
(index/snapshot-datoms local-stream)              ; => flattened datom seq

;; the format's readers (every platform)
(index/published-index {:dao.jing/type :dao.jing/file :path path}
                       manifest-address)                  ; bounded d5 descriptor
(index/read-manifest content-store manifest-address)     ; validated manifest
(index/read-datoms content-store manifest-address)       ; eager EAVT walk
(index/walk-index-datoms content-store segment-address)  ; eager node-graph walk
(index/restored-indexes content-store manifest)          ; lazy B-tree re-attach

;; the shared vocabulary
(index/index-datoms datoms)                     ; {:eavt :aevt :avet :vaet} trees
(index/subseq-from sorted-set index/eavt-cmp sentinel)
(index/compare-vals a b)
(index/eavt-cmp) (index/aevt-cmp) (index/avet-cmp) (index/vaet-cmp)
(index/datom-e d) (index/datom-a d) (index/datom-v d)
(index/datom-t d) (index/datom-m d)
```

`publish-index!` semantics worth pinning:

- **Builds into a temporary recording content handle.** The four indexes are
  stored through `dao.data.btree.storage/kv-storage` into a build-time
  in-memory handle whose put records each unique blob on first insertion
  (deduplicating equal blobs as `:present`), so the recorded order is exactly
  the children-before-parent store-tree traversal.
- **Exactly one intake stream.** `publish-index!` selects one stream from the
  explicit pool — the first, or the stream returned by `:select-stream`, which
  must be a member of the pool. It appends every unique node blob in recorded
  order, then the manifest last.
- **Append success means enqueued, not materialized.** Success acknowledges
  that every payload was appended to the selected intake stream. A DaoJing
  observer must still read the stream for the payloads to land in content
  storage.
- **Partial immutable prefix is retry-safe.** The manifest is always appended
  last, so a full intake stream can only have left node blobs; the next
  publish re-emits them and materialization deduplicates. No partial manifest
  is ever observable.
- **No direct DaoJing mutation.** Publication writes streams, never a content
  store; DaoJing observes.
- **Idempotent on unchanged data** — content addressing yields the same
  segment keys, so the same manifest address; republishing costs writes that
  are no-ops at materialization.
- **An empty stream publishes nil index addresses** —
  `{:indexes {:eavt nil …}}` reads back as no datoms, not an error (a walk of
  nil ⇒ ()).
- **Publication changes representation, not visibility** — append has already
  written the datoms to the local stream. Publication replaces no root; it
  adds a persisted covered-index manifest over the same datoms.

## The agent-transactor loop

The write path runs through `dao.space.transactor`'s `:transactor` stream
wrapper (see `dao.space.md`, *The Write Path*). Its descriptor is
`{:dao.stream/type :transactor :local-stream s :intake-pool [...] optional :name}`; it
owns neither stream lifecycle — the local stream and intake pool are
supplied, never created, registered, or closed:

```clojure
(require '[dao.stream :as ds]
         '[dao.space.transactor :as transactor])

(def local (ds/open! {:dao.stream/type :ringbuffer}))          ; the agent's own log
(def intake-pool [(ds/open! {:dao.stream/type :ringbuffer})])  ; DaoJing intake streams

(def log (ds/open! {:dao.stream/type :transactor
                    :local-stream local
                    :intake-pool intake-pool
                    :name "worker-7"}))             ; one wrapper per local stream

(ds/append! log {:db/id id :work/claims task})      ; 1. deposit — one atomic
;; ... more appends ...                             ;    transaction record
(transactor/publish! log)                           ; 2. snapshot, build, enqueue
```

The wrapper's `ds/append!` / `transact!` write exactly one atomic transaction
record to the local stream per call, so no reader observes a torn transaction.
On open it scans the retained history from cursor zero and derives the next
`t` (0 for an empty history, else 1 + the maximum datom t); full retention is
therefore currently required. One wrapper per local stream is a hard
single-writer invariant. Calls through one wrapper serialize timestamp
allocation and append on shared-memory hosts; two wrappers over the same
stream still each derive the same `t` and write colliding records, which
cannot be silently coordinated without shared mutable state. `publish!` delegates explicitly to
`index/publish-index!`, passing the wrapper's local stream, intake pool, and
opts, and returning its `{:manifest-address ... :manifest ...}`.

Two lifecycle facts are deliberate:

- Publication is an acceleration, never a semantic change — `q`/`match`
  answer identically before and after (pinned by
  `publish-index-snapshot-reads-local-stream-and-reads-back` and the
  observer-materialization parity tests).
- The published-index DaoStream adapter currently walks EAVT eagerly and
  retains canonical d5 elements. Query does not dispatch on
  manifests, pools, or B-tree segments. Publishing changes access cost, never
  the datoms.

## Dependency picture

```
dao.space.transactor  ──►  dao.space.index  ◄──  dao.space.query
   (write path:          (realization:          (the Peer:
    append!/transact!      B-tree values,          open DaoStream descriptor,
    write transaction      publish-index!,         match, q, pull —
    records; publish!      sort orders,            reads wrapped raw datom sources
    delegates here)        node blobs)             or published manifests)
                                │
                                ▼
                          dao.data.btree ◄── dao.data.btree.storage
                          (the tree)            (IStorage over a content handle)
                                │
                                ▼
                          dao.jing (content-store handle)
```

- `dao.space.query` requires `dao.space.index` for manifest reading and index
  traversal. It does not define or persist a second index format.
- `dao.space.transactor` requires `dao.space.index` for snapshotting,
  validation, and publication. The write path does not depend on the Datalog
  engine.
- `dao.space.index` requires `dao.data.btree`,
  `dao.data.btree.storage`, and `dao.jing`; it never requires
  `dao.space.query`. No cycle is possible: realization below, interpretation
  above.

Storage stays dumb throughout (Ruling 1 of `dao.space.query.md`): everything
here is built from `dao.jing/materialize!` and `dao.jing/get` against strict
segment addresses. Storage never knows the segment blobs form an index, and
there is no mutable root for it to maintain.

## Platform status

Build, the eager published-stream adapter, lazy lower-level restoration, and
range slicing are all
**cross-platform**. `dao.data.btree` and `dao.data.btree.storage` are `.cljc`
implementations shared by JVM, ClojureScript, and ClojureDart.

The published-stream path uses `walk-index-datoms`, which understands the plain EDN node
blobs using only `jing/get`. The separate lazy API uses
`dao.data.btree/restore-tree` through
`dao.data.btree.storage/kv-storage`; traversal faults only the required nodes.
The manifest's `:count` and `:branching-factor` are threaded through
`restore-tree` deliberately: count keeps O(1) `count` on restored trees
without faulting the graph, and the branching factor reaches every restored
node so mutation splits at the published thresholds. Tests in
`test/dao/space/index_test.cljc` pins both contracts, including lazy
point-lookup fetch counts for the lower-level restored tree and descriptor
transport/eager logical d5 reads for the DaoStream adapter.

## Open items

- **Segment GC** — superseded index segments accumulate forever.
- **Arranged published streams and K-way merge** — queries currently consume
  each published manifest through the eager logical stream adapter.
- **Incremental indexing** — the natural next increment for long-lived agent
  transactors: today an owner republishes wholesale from the full datom seq. A
  future builder could retain the previous manifest, insert only the appended
  datoms into restored B-trees, and store their changed paths. Nothing in the
  node or manifest format requires that strategy; the current implementation
  always performs a full rebuild.
- **Async hydration** — remote reads over async backends use the hydration
  adapter (`dao.data.btree.storage/hydration-storage`, `hydrate!`); the async
  variants (`hydrate-async`, `store-tree-async`) are deferred until an async
  DaoJing backend exists.
