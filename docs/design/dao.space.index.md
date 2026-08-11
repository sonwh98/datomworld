# dao.space.index — The Transactor-Side Indexing Library

Status: implemented. The mechanism (owner-built, content-addressed B-Tree
segments) shipped 2026-07-10 inside `dao.space.query`; extracted 2026-07-12
into its own namespace. This document records why the library exists, what it
owns, its public surface, and the boundary between it and the query library.
The executable contract is `test/dao/space/index_test.cljc`.

**Related documents:**
- `docs/design/dao.space.query.md`, *Index Realization* — the design record
  this library implements (rebuild / incremental / owner-built strategies,
  Ruling 1: no new storage protocol, laziness is reader-side)
- `docs/design/dao.space.md` — the tuple space; *Three Boundaries* maps
  Transactor/Storage/Query onto streams
- `docs/design/dao.jing.md` — the storage boundary; *The Segment and Root
  Keyspace* (`:segment/sha256-<hash>` / `:root/<name>`)
- `docs/datomic.md` — the architecture the analogy below leans on

## Why a separate library

In Datomic, the **transactor** builds the covered indexes and saves them to
storage; **peers** pull segments and answer queries. datom.world decentralizes
the transactor: every agent appending to its own `dao.stream` is its own
transactor (`dao.space.md`, *Three Boundaries*) — so every agent also owns the
transactor's other duty, **indexing its own datoms**. That duty needs a
library the same way querying does:

- `dao.space.index` — what a stream owner runs: build the covered indexes,
  persist them as immutable content-addressed segments, advance the root.
  The write-side counterpart of the pair.
- `dao.space.query` — the embeddable Peer: fold sources, match, Datalog.
  Reads what the index library realizes; owns no index format knowledge of
  its own.

Until 2026-07-12 the indexing code lived inside `dao.space.query`, whose own
docstring apologized for it ("the one writing entry point here"). The
extraction restores the boundary: **query never writes; index owns the
realization both sides share.** It is the same move Datomic makes between
transactor and peer — one party materializes, many parties consume — except
here "the transactor" is not a process but a library duty every agent carries.

## What the library owns

One namespace, `src/cljc/dao/space/index.cljc`. Everything below is the index
*realization*: the shared vocabulary a builder and a reader must agree on.

- **The root conventions** — each stream owns its root, `:root/<name>`, named
  explicitly by a `dao.space.query/root-source` descriptor. A collection of
  root sources is the pool a query folds; no shared membership key is written.
  The two root shapes every datom root carries are wholesale `{:datoms [...]}`
  and owner-built `{:indexes {:eavt <segment-key> :aevt ... :avet ... :vaet ...}
  :count n :branching-factor n :reorder-epoch n}`. Roots written by the
  transactor carry `:reorder-epoch`; readers treat its absence as epoch zero
  for compatibility with older or hand-built roots.
- **Sort orders** — `eavt-cmp` / `aevt-cmp` / `avet-cmp` / `vaet-cmp`
  over heterogeneous
  values (`compare-vals`: type-ranked, nil-first — entity ids are
  caller-chosen and can be any type), plus the datom slot accessors
  (`datom-e/a/v/t/m`) they read through.
- **The in-memory index value** — `index-datoms` builds
  `{:eavt :aevt :avet :vaet}` `dao.data.btree` sorted sets. `subseq-from`
  delegates to `dao.data.btree/slice`: a log-n descent that, on a restored
  tree, loads only the seek path plus the matching range. The implementation
  is the same on JVM, ClojureScript, and ClojureDart. This is a value built
  when needed, not a separately maintained per-agent cache.
- **The persisted node-blob format, both directions** — a
  `dao.data.btree/IStorage` adapter over a jing handle: nodes store as
  plain-EDN content-addressed segment blobs (leaf `{:keys [...]}`, branch
  `{:level n :keys [...] :addresses
  [...]}`), keys minted by `jing/segment-key` — Merkle by construction, since
  `dao.data.btree` stores children before parents. `restored-indexes`
  re-attaches a complete published manifest lazily on every platform;
  `walk-index-datoms` reads the node graph eagerly using only `jing/get`;
  `read-root` and `read-datoms` read either root shape.
- **The transactor entry point** — `publish-index!`: build the four covered indexes
  from the stream's datoms, persist the segments through
  `dao.data.btree.storage/kv-storage`, then `cas!` the root to the manifest.

## Public surface

```clojure
(require '[dao.space.index :as index])

;; the transactor's move (JVM, ClojureScript, and ClojureDart)
(index/publish-index! store :root/w)            ; index the stream at that root
(index/publish-index! store datoms {:key :root/w})  ; index an explicit datom seq
(index/publish-index! store datoms
                      {:key :root/w
                       :branching-factor 512})  ; max keys per node (default 512,
                                                ; Datomic-style fat segments)
;; => {:eavt :segment/sha256-… :aevt … :avet … :vaet …}

;; the format's readers (every platform)
(index/read-root store :root/w)                 ; atomic datoms/CAS snapshot
(index/read-datoms store some-root-key)         ; either root shape -> datoms
(index/walk-index-datoms store segment-key)     ; eager node-graph walk
(index/restored-indexes store manifest)         ; lazy B-tree re-attach

;; the shared vocabulary
(index/validate-root-key! :root/w "context")    ; validate root key shape/name
(index/index-datoms datoms)                     ; {:eavt :aevt :avet :vaet} trees
(index/subseq-from sorted-set index/eavt-cmp sentinel)
(index/compare-vals a b)
```

`publish-index!` semantics worth pinning:

- **Idempotent on unchanged data** — content addressing yields the same
  segment keys, so the same root addresses; republishing costs writes that
  are no-ops.
- **Single-writer discipline** — throws if the root `cas!` is lost to a
  concurrent writer, rather than silently retrying over someone else's
  publish. The stream owner publishes; nobody else should be racing it.
- **An empty stream publishes `nil` roots** — `{:indexes {:eavt nil …}}`
  reads back as no datoms, not an error (walk of nil ⇒ ()).
- **Publication changes representation, not visibility** — append has already
  written the datoms to the stream root in wholesale form. Publication replaces
  that root value with addresses of covered indexes over the same datoms.

## The agent-transactor loop

How the pieces compose for a long-lived agent (the write path is
`dao.space.transactor`'s `:transactor`; see `dao.space.md`, *The Write Path*):

```clojure
(require '[dao.stream :as ds]
         '[dao.space.transactor :as transactor])

(def log (ds/open! {:type :transactor :store store :name "worker-7"}))

(ds/append! log {:db/id id :work/claims task})   ; 1. deposit — appends datoms
;; ... more appends ...
(transactor/publish! log)                         ; 2. build and persist indexes
;; readers whose explicit pool includes :root/worker-7 can consume them
```

The two root representations form a simple owner-controlled lifecycle:

- **Wholesale root** — `ds/append!` stores the stream's datoms as
  `{:datoms [...]}` in `dao.jing`. Queries can read this immediately; the eager
  query path builds an ephemeral `dao.data.btree` index value from those datoms.
- **Indexed root** — `publish-index!` builds four `dao.data.btree` sets,
  persists their immutable nodes as content-addressed segments, and replaces
  the wholesale root with a manifest. A query over one explicit root source can
  restore a complete non-empty manifest lazily. The datoms and answers do not
  change; only their representation and access cost do.

Two interactions are deliberate:

- A `:transactor` append onto a published root **folds the indexed datoms
  back to the wholesale shape** rather than dropping them — appends never
  lose indexed datoms; they move the root back to wholesale until the owner
  republishes. Old immutable segments remain until segment GC exists.
- `dao.space.query/fold` prefers the lazy restored path only for a complete
  non-empty manifest with no `as-of` bound; every other read (an empty or
  incomplete manifest, federation, or `as-of`) takes the eager path.
  Publishing is an acceleration, never a semantic change — q/match answer
  identically before and after (pinned by
  `publish-index-root-shape-and-parity`).

## Dependency picture

```
dao.space.transactor DaoStreamLog
    │
    ▼ append!
dao.jing root {:datoms [...]}
    │
    ├── query: eagerly build ephemeral dao.data.btree indexes
    │
    └── publish-index!
            │
            ├── persist immutable dao.data.btree nodes as segments
            └── CAS root to {:indexes {...} :count ...}
                    │
                    └── query: lazily restore one complete non-empty manifest,
                               eagerly read other source shapes
```

There is no long-lived local index cache in the current implementation.
`index-datoms` constructs an in-memory B-tree value for an eager fold;
`publish-index!` independently constructs and stores covered B-trees from the
root's complete datom sequence.

```
dao.space.transactor  ──►  dao.space.index  ◄──  dao.space.query
   (write path:          (realization:          (the Peer:
    ds/append! appends;      B-tree values,          fold, match, q —
    folds indexed         publish-index!,        reads wholesale or
    roots back)           sort orders,           indexed roots)
                          node blobs)
                               │
                               ▼
                          dao.jing (jing handle)
```

- `dao.space.query` requires `dao.space.index` for root realization and
  index traversal. It does not define or persist a second index format.
- `dao.space.transactor` requires `dao.space.index` for root reading,
  validation, and publication. The write path does not depend on the Datalog
  engine.
- `dao.space.index` requires `dao.data.btree`,
  `dao.data.btree.storage`, and `dao.jing`; it never requires
  `dao.space.query`. No cycle is possible: realization below, interpretation
  above.

Storage stays dumb throughout (Ruling 1, `dao.space.query.md`): everything
here is built from `jing/get`, `jing/cas!`, and `jing/segment-key`. The B-tree
storage adapter writes immutable nodes with `cas!` against `jing/absent`;
the stream root moves by ordinary `cas!`. Storage never knows the segment
blobs form an index.

## Platform status

Build, eager traversal, lazy restoration, and range slicing are all
**cross-platform**. `dao.data.btree` and `dao.data.btree.storage` are `.cljc`
implementations shared by JVM, ClojureScript, and ClojureDart.

The eager path uses `walk-index-datoms`, which understands the plain EDN node
blobs using only `jing/get`. The lazy path uses
`dao.data.btree/restore-tree` through
`dao.data.btree.storage/kv-storage`; traversal faults only the required nodes.
`publish-index-works-on-every-platform`, `publish-index-lazy-fetch`, and
`index-root-readable-from-plain-node-blobs` pin these contracts.

## Open items

- **Segment GC** — superseded index segments accumulate forever.
- **K-way merge of lazy indexes** — federated queries over `{:indexes ...}`
  roots fall back to the eager walk.
- **Incremental indexing** — the natural next increment for long-lived agent
  transactors: today an owner republishes wholesale from the full datom seq,
  and a `:transactor` append moves an indexed root back to wholesale. A future
  builder could retain the previous manifest, insert only the appended datoms
  into restored B-trees, and store their changed paths. Nothing in the node or
  manifest format requires that strategy; the current implementation always
  performs a full rebuild.
