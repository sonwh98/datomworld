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

- **The root-manifest convention** — `default-datoms-key` (`:root/datoms`)
  and the two root shapes: wholesale `{:datoms [...]}` and owner-built
  `{:indexes {:eavt <segment-key> :aevt ... :avet ...} :count n}`.
- **Sort orders** — `eavt-cmp` / `aevt-cmp` / `avet-cmp` over heterogeneous
  values (`compare-vals`: type-ranked, nil-first — entity ids are
  caller-chosen and can be any type), plus the datom slot accessors
  (`datom-e/a/v/t/m`) they read through.
- **The in-memory index** — `index-datoms` (`{:eavt :aevt :avet}` sorted
  sets) and `subseq-from` (psset `slice` where available — a log-n descent
  that on a lazily-restored set loads only the seek path plus the matching
  range; linear fallback on ClojureDart).
- **The persisted node-blob format, both directions** — a psset `IStorage`
  over any `IKVStore`: nodes store as plain-EDN content-addressed segment
  blobs (leaf `{:keys [...]}`, branch `{:level n :keys [...] :addresses
  [...]}`), keys minted by `jing/segment-key` — Merkle by construction, since
  psset stores children before parents. `restored-indexes` re-attaches a
  published manifest lazily (JVM); `walk-index-datoms` reads the node graph
  eagerly on every platform; `read-datoms` reads either root shape.
- **The transactor entry point** — `publish-index!`: build the three indexes
  from the stream's datoms, persist the segments (`put!`), `cas!` the root to
  the manifest.

## Public surface

```clojure
(require '[dao.space.index :as index])

;; the transactor's move (JVM-only for now)
(index/publish-index! store)                    ; index the root's current datoms
(index/publish-index! store datoms)             ; index an explicit datom seq
(index/publish-index! store datoms
                      {:branching-factor 512})  ; max keys per node (default 512,
                                                ; Datomic-style fat segments)
;; => {:eavt :segment/sha256-… :aevt … :avet …}

;; the format's readers (every platform)
(index/read-datoms store)                       ; either root shape -> datoms
(index/read-datoms store some-root-key)
(index/walk-index-datoms store segment-key)     ; eager node-graph walk
(index/restored-indexes store indexes)          ; lazy psset re-attach (JVM)

;; the shared vocabulary
index/default-datoms-key                        ; :root/datoms
(index/index-datoms datoms)                     ; in-memory {:eavt :aevt :avet}
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

## The agent-transactor loop

How the pieces compose for a long-lived agent (the write path is
`dao.space.stream`'s `:dao-stream`; see `dao.space.md`, *The Write Path*):

```clojure
(def log (ds/open! {:type :dao-stream :store store :name "worker-7"}))

(ds/append! log {:db/id id :work/claims task})   ; 1. deposit — appends datoms
;; ... more appends ...
;; local indexes are available immediately (dao.space.query reads them)
(index/publish-index! store)                  ; 2. periodically: persist to dao.jing
;; other agents now see this data through dao.jing
```

The two stages mirror Datomic's memory-index → disk-index pipeline, decentralized:

- **Local indexes** (in-memory sorted sets over the stream's datoms) are the
  agent's own cache. `dao.space.query` reads these directly — the agent's
  recent writes are queryable without publishing.
- **Persistent indexes** (content-addressed B-Tree segments in `dao.jing`) are
  what `publish-index!` produces. Other agents reach this data through
  `dao.jing`; the local cache and the persisted segments cover the same
  datoms at different lifecycle stages.

Two interactions are deliberate:

- A `:dao-stream` append onto a published root **folds the indexed datoms
  back to the wholesale shape** rather than dropping them — appends never
  destroy an index, they just downgrade the root until the owner republishes.
- `dao.space.query/fold` prefers the lazy restored path only for a complete
  manifest with no `as-of` bound; every other read (federation, as-of,
  non-JVM) walks eagerly. Publishing is an acceleration, never a semantic
  change — q/match answer identically before and after (pinned by
  `publish-index-root-shape-and-parity`).

## Dependency picture

```
dao.stream (local append-only log)
    │
    ▼
dao.space.index
    ├── local indexes (in-memory sorted sets — cache the stream's datoms,
    │                  dao.space.query reads these directly)
    │
    └── publish-index! (persist → content-addressed B-Tree segments)
            │
            ▼
        dao.jing (persistent covered indexes)
            │
            ▼
        dao.space.query reads from both layers:
            • local indexes (own agent's recent writes)
            • persistent indexes in dao.jing (all agents' published data)
```

The local indexes and the persisted indexes are the same structure at different
lifecycle stages — like Datomic's memory-index → disk-index pipeline, but
decentralized (each agent has its own). `dao.space.query` hits both layers: the
local cache for the agent's most recent writes, and the persisted segments for
everything published (its own previously-published data and every other agent's).

```
dao.space.stream  ──►  dao.space.index  ◄──  dao.space.query
   (write path:          (realization:          (the Peer:
    ds/append! appends;      local sorted sets,     fold, match, q —
    folds indexed         publish-index!,        reads local and
    roots back)           sort orders,           persistent layers)
                          node-blob format)
                               │
                               ▼
                           dao.jing (IKVStore)
```

- `dao.space.query` requires `dao.space.index` and sheds its
  `persistent-sorted-set` dependency entirely — every psset touchpoint lives
  in the index library.
- `dao.space.stream` requires only `dao.space.index` (`read-datoms`,
  `default-datoms-key`) — the write path does not drag in the Datalog engine.
- `dao.space.index` requires `dao.jing` and (JVM/cljs) psset; never
  `dao.space.query`. No cycle is possible: realization below, interpretation
  above.

Storage stays dumb throughout (Ruling 1, `dao.space.query.md`): everything
here is built on the four `IKVStore` methods. Segments are ordinary
content-addressed blobs; the root moves by ordinary `cas!`; storage never
knows the segments form an index.

## Platform status

Build is **JVM-only** (`publish-index!` throws elsewhere): psset durability
(`store`/`restore-by`) is a Clojure-only feature of that library. Readability
is **universal**: node blobs are plain EDN, so cljs/cljd (and the as-of /
federated paths everywhere) read published indexes eagerly via
`walk-index-datoms` — pinned by `index-root-readable-from-plain-node-blobs`,
which hand-builds a node graph with no psset involved and queries it on every
platform. Reader-conditional discipline: `:cljd` FIRST in every conditional
(the ClojureDart host pass also matches `:clj`).

## Open items (unchanged from Index Realization)

- **Segment GC** — superseded index segments accumulate forever.
- **Non-JVM laziness** — cljs/cljd walk the whole node graph eagerly.
- **K-way merge of lazy indexes** — federated queries over `{:indexes ...}`
  roots fall back to the eager walk.
- **Incremental indexing** — the natural next increment for long-lived agent
  transactors: today an owner republishes wholesale from the full datom seq,
  and a `:dao-stream` append downgrades an indexed root to wholesale. A
  per-append (or every-N-appends) maintenance strategy — psset conj onto the
  restored sets plus a re-store of the changed path — would keep publishing
  cost proportional to the delta, Datomic's memory-index/merge move. Nothing
  in the format needs to change for it; it is purely a builder strategy.
