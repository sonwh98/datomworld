# Coordinate Addressing & Materialized Views — Design Discussion

Status: rulings recorded 2026-07-09 (see *Rulings*, below), resolving the tentative "Lean"
framing of the 2026-07-04 discussion that precedes them. The Target Architecture
(owner-built, lazily-pulled B-Tree segments) shipped 2026-07-10 on the JVM via
`dao.space.query/publish-index!` — see *Index Realization*, below, for what is implemented
and what remains (non-JVM laziness, segment GC, k-way federated merge).
Origin: follow-on from the n-tuple query work (`docs/design/dao.space.md`, *Arity: n-tuples*) and
`docs/glm-review.md`.

## Architecture

The query library reads `dao.jing` as an embeddable Peer, pulling byte segments up from the storage boundary and running matching/Datalog above it:

```
   dao.space.query/q   …/match          ← the TUPLE SPACE reads (this doc)
        │  lazily pulls B-Tree segments from the
        │  IKVStore, merges them, and runs Datalog
        ▼
╔═══════════════════ dao.jing ═══════════════════╗   ← STORAGE boundary (dao.jing, separate doc)
║             IKVStore (put! / get / cas!)        ║
║                                                 ║
║   [Mutable Stream Roots]   [Immutable Chunks]   ║
╚════════╪══════════════════════════════╪═════════╝
         │ byte maps                    │ byte maps
┌────────▼──────────────────────────────▼─────────┐
│              KV Backends (Mem, File)            │
└─────────────────────────────────────────────────┘

   each writer = its own Transactor (decentralized write boundary, via dao.stream)
```

## The question

Can `match`/`q` query across streams whose tuples have **different fixed arities** — one stream
of 3-tuples, another of 4-tuples, another of canonical 5-tuples — joining them on a variable that
sits at **different positions** in each shape?

```clojure
;; stream A (3-tuples)         stream B (4-tuples)
;; [:sonny :likes :pizza]      [:dominos :pizza 10 3]
;; [:sonny :likes :sushi]      [:dominos :sushi 10 3]
(query/q '[:find ?p
           :where [:sonny  :likes ?p]     ; ?p at slot 2 of A
                  [:dominos ?p 10 3]]     ; ?p at slot 1 of B
         [stream-a stream-b])
;; => #{[:pizza] [:sushi]}
```

This is already specced at the *matching* layer (unification by variable name across positions,
in `docs/design/dao.space.md`). The discussion below is about the *addressing* layer: how a query
says where the heterogeneous tuples live.

## The coordinate model (proposed)

A coordinate is a value that addresses into the space:

```clojure
[jing-storage [
  [dao.stream1 [3-tuple 3-tuple 3-tuple]]
  [dao.stream2 [5-tuple 5-tuple 5-tuple]]]]
```

A `dao.jing` storage hosts many named logs, each exposed through the `dao.stream` interface, and
each log may hold tuples of a different fixed dimension. A query names the storage and the logs to
fold; the engine folds them and joins by variable name.

## Reality check: this is proposed, not current

The coordinate does **not** describe today's code:

- `dao.jing` is a flat KV — each key a single mutable root updated via `cas!`, holding an opaque
  value. The query reads one `:root/datoms` per store (the doc itself flags this as "the simplest
  possible convention ahead of the target B-Tree-segment architecture").
- `dao.stream` is a separate abstraction (ringbuffer / append-log / file / http / ws / udp) that
  carries opaque **bytes** and does **not** persist into a jing store. The dependency is inverted —
  `dao.jing/file` *uses* `dao.stream.log` internally as its byte transport.
- There is no per-log addressing, no log manifest, no coordinate notion anywhere yet.

So the coordinate is the architecture the doc defers.

## Why it coheres

It gives every part of the existing design a concrete home:

- **Write side** (generative communication): a writer appends to its own named `dao.stream` log —
  the doc's "each writer owns a single-writer log."
- **Read side** (associative matching): the query folds a *set of named logs*; the coordinate is
  how a read says "these logs, please" — the doc's "membership is intake, not identity," made
  addressable.
- **Per-log fixed arity, cross-log heterogeneity**: within a log, tuples are homogeneous; across
  logs they differ, and the n-tuple matcher folds and joins by name. So the **coordinate is the
  addressing layer; the n-tuple engine is the matching layer — they compose.**
- **A coordinate is a pure value**: cacheable, replayable, sendable through a stream — "everything
  is data / streams are values" made literal.
- It **generalizes federation**: today `[store1 store2]` federates *stores*; the model federates
  *logs within a store* (and nests). One store is a small federation of its own single-writer logs.

## The structural-vs-semantic line

The crux: can the KV be "coordinate-aware" without violating "storage doesn't interpret"? Yes —
because the coordinate is **structural**, not semantic.

- *Structurally aware* — the KV knows "named logs, each an ordered collection of tuples with a
  comparator; here's a prefix seek / a per-log index." This is still **serving**, not matching: it
  returns tuples, never treats them as datoms, never binds a variable, never joins.
- *Semantically aware* — the KV knows position 1 is an attribute, or how to run Datalog. That
  collapses interpretation into storage. Forbidden.

The coordinate `[storage [[log tuples]…]]` — named logs of positional tuples — is a structural
shape (a named sorted multiset). So the KV can speak the coordinate protocol (order, seek, index)
without knowing what the tuples mean. Datomic does the same: opaque content-addressed segments
("dumb") plus a structural segment/index contract that the Peer interprets.

## Convention-over-KV + coordinate-aware (the synthesis)

- At the **semantic** layer: convention-over-KV. Datoms, e/a/v, Datalog, unification all stay in
  interpreters; storage never interprets.
- At the **structural** layer: the KV is coordinate-aware. It knows the named-log base shape and
  optionally serves materialized views (covered indexes, ordered seeks) over it.

Decomposition: the **coordinate is the base** (canonical facts, the medium); the **served indexes
are materialized views** derived from it. "Coordinate-aware KV" = the KV knows the base shape and
optionally serves views. The load-bearing half for performance is the views.

## It is the doc's own concept: materialized views

"A Family of Interpreters" already calls covered indexes and derived access structures
**materialized views**. So "coordinate-aware KV serving EAVT/AEVT/AVET and tuple-order indexes" is
exactly a materialized view — the only addition is *where* it gets materialized. Once named this
way, the open question reduces to **which layer materializes views**:

- *Interpreter-materialized* = the rebuild-per-query fold. Always fresh, slower.
- *Storage-materialized* = the coordinate-aware KV serves them. Faster. Datomic's move: storage
  holds opaque index segments; the Peer interprets.

"Index Realization" below (rebuild-per-query / incremental / owner-built-peers-merge)
*is* this decision. An opt-in capability protocol is the negotiation: backends that can materialize
views at the storage layer offer the capability; otherwise the interpreter materializes them
itself. Same views, different layer.

## Index Realization

`dao.jing` exposes only the substrate an index is built from — immutable, content-addressed
byte segments plus one mutable root pointer (see [`dao.jing.md`](dao.jing.md), *The Segment
and Root Keyspace*) — and knows nothing of indexes. The *index* itself, a covered B-Tree
(EAVT/AEVT/AVET/VAET) a query can traverse and answer from, is the interpretation this
library projects onto those bytes. How a reader builds and maintains it is a pure performance
choice made *above* the storage boundary; all strategies answer identically, the index being
the same set of datoms (see ADR 0001's monoid homomorphism):

- **Rebuild per query** — each read folds the store's datoms into a fresh index and discards
  it. Simple; O(total datoms) per read.
- **Incremental index** — a long-lived reader keeps a cursor per member stream and folds
  only new frames as they arrive (Datomic-peer style). More machinery; amortized reads.
  Still no transactor and no global clock — each stream advances its own cursor.
- **Owner-built, peers merge (Target Architecture)** — each stream's owner indexes its own
  stream and persists the segments; readers **merge** per-stream indexes instead of
  rebuilding. Index-once, reuse-by-many, and available when the author is offline — the
  decentralized analog of Datomic's transactor-built index (see ADR 0001, ruling 5 and Open
  Question 1).

**Status: implemented** (`dao.space.query/publish-index!`, 2026-07-10). The owner builds the
three covered indexes with **tonsky/persistent-sorted-set** (`from-sequential`, default
`:branching-factor 512` — Datomic-style fat segments) and persists them through psset's
`IStorage` hook implemented over `IKVStore`:

1. `publish-index!` (the index builder — a reader-layer concern, in this library) writes each
   B-Tree node as an immutable, content-addressed segment blob (`put!` under
   `jing/segment-key`; Merkle by construction, since psset stores children before parents),
   then advances the stream root to `{:indexes {:eavt <segment-key> ...} :count n}` via
   `cas!`. Republishing unchanged data is idempotent: same content, same keys, same roots.
2. On the JVM, a single-handle query restores those indexes lazily (`psset/restore-by`):
   nothing is fetched until a traversal reaches it, and `slice`-based seeks load only the
   descent path plus the matching range — measured in the test suite as 2 segment fetches
   out of 26 for a bound-`e` match over 600 datoms at `:branching-factor 32`
   (`publish-index-lazy-fetch`, which asserts the point lookup stays under 6 fetches while
   the tree exceeds 15 segments).
3. Node blobs are plain EDN maps (leaf `{:keys [...]}`, branch
   `{:level n :keys [...] :addresses [...]}`), so **readability is universal**: cljs/cljd —
   and the as-of and federated paths on any platform — read an `{:indexes ...}` root by
   eagerly walking the node graph with ordinary `jing/get` (`walk-index-datoms`), no psset
   involvement. Both root shapes (`{:datoms [...]}` wholesale and `{:indexes ...}`) stay
   readable everywhere.

**Laziness is JVM-only for now.** psset's durability API (`IStorage`/`restore`) is a
Clojure-only feature of that library — absent from its ClojureScript implementation in the
latest release (0.3.0) and master (its README scopes durability to "Clojure version"), and
`cljd` has no port at all — quite apart from the deeper issue that JS's async-only IO cannot
block mid-traversal the way a synchronous node fault requires. Non-JVM platforms therefore
read eagerly (correct, not lazy). Known gap, deliberately out of scope: orphaned segments
from superseded publishes are never collected (`psset/walk-addresses` is the future GC hook),
and federated queries over multiple lazy indexes fall back to the eager walk rather than
k-way merging.

**Platform degradation (`cljd`).** This library uses `tonsky/persistent-sorted-set` for its
in-memory sorted-set on clj/cljs. That library lacks a Dart (`cljd`) implementation, so in
`cljd` environments it degrades to the built-in `clojure.core/sorted-set-by` (a standard
red-black tree), and index reads take the eager walk described above.

## The `read-datoms` Contract

`dao.space.md`'s `fold` sketch calls a function, `read-datoms`, to turn what `IKVStore/get`
returns into the `[e a v t m]` vectors the index is built from (`docs/design/dao.space.md`,
*The Query Library*: "`read-datoms` parses B-Tree segments pulled from `dao.jing` into datoms —
storage has no datoms API of its own"). That phrasing describes the Target Architecture this
document defers to below (segmented, lazily-pulled B-Tree chunks) — the actual function today
does something narrower, spelled out next. Either way, this is the exact seam Datomic's Peer
occupies internally when it decompresses and decodes storage's index-segment blobs before
building indexes from them — see `docs/datomic.md`. `dao.jing.md` correctly says nothing about
this: decoding bytes into datoms is meaning-making, and storage stays pure syntax
(`dao.jing.md`, *What DaoJing Is*). The contract belongs here, at the Peer layer.

**Where the byte decode actually happens, today: inside the `IKVStore` backend, not in
`read-datoms` itself.** The real function (`src/cljc/dao/space/query.cljc:69-73`):

```clojure
(defn read-datoms
  ([store] (read-datoms store default-datoms-key))
  ([store datoms-key] (:datoms (jing/get store datoms-key {:datoms []}))))
```

is a pure `:datoms` extraction over whatever `jing/get` returns — it never touches bytes or
calls `edn/read-string` itself. `IKVStore/get`'s contract is "returns the v-map," already
decoded data, not bytes (`dao.jing.md`, *The Storage Interface*); `read-datoms` just pulls the
`:datoms` key out of that map. The `pr-str`→`edn/read-string` round-trip is a `KVFile`-specific
wire format, not something every `IKVStore` backend does: `KVFile.get`
(`src/cljc/dao/jing/file.cljc:206-226`) reads its on-disk bytes and calls
`(edn/read-string payload)` before returning the v-map, while `KVMem.get` returns the map it
already holds in an atom — no bytes, no decode, ever. So for `KVMem`, "`dao.jing` hands back
opaque bytes" isn't even true; only `KVFile` (and, differently, `dao.jing.dht`) has bytes to
decode at all, and that decode is already finished by the time `read-datoms` sees the result.

**Current contract** (matches `dao.jing.md`, *Current Scope*):

- **Input.** A single `IKVStore` `get` of the stream's root key (`:root/datoms`), returning an
  already-decoded v-map shaped `{:datoms [...]}` — one flat vector of already-formed datom
  tuples, not yet segmented into B-Tree chunks. Whether that map arrived via a `pr-str`/`edn`
  round-trip (`KVFile`), a plain in-memory reference (`KVMem`), or a network fetch
  (`dao.jing.dht`) is backend-internal and invisible to `read-datoms`.
- **Extract.** `read-datoms` pulls the `:datoms` vector out of that map and returns it as-is —
  no parsing, no B-Tree traversal, no lazy pull, consistent with today's "rebuild per query"
  strategy (*Index Realization*, above). (Where a backend does decode bytes, it
  uses safe deserializers — such as `clojure.edn/read-string` for `KVFile` or
  Transit for `dao.jing.dht` — never unsafe `read-string`/`eval`.)
- **Namespace stamping: not yet applied.** `dao.space.md` describes each stream's datoms as
  stamped with the stream's namespace before indexing so that two streams' local id `1025`
  stay distinct. `dao.jing.md`'s Current Scope defers this ("the reader's per-member namespace
  is not yet derived from the canonical kickoff hash, because content addressing is not yet
  load-bearing"). `read-datoms` today therefore returns datoms *unstamped*; cross-stream id
  collision is a known, currently-open gap, not a `read-datoms` bug, until content addressing
  lands.
- **`t`/`m` defaults.** Frames missing a transaction coordinate or metadata slot fall back to
  `dao.datom/default-op`, the same convention `dao.space.md`'s entity-map normalization uses
  (*Source Polymorphism*).

**Segmented roots** (implemented 2026-07-10; see *Index Realization*, above): `read-datoms`
now also accepts the owner-built root shape `{:indexes {:eavt <segment-key> ...}}`, reading
it by eagerly walking the `:eavt` node graph with per-node `jing/get` calls
(`walk-index-datoms`) and returning the collected datoms — same `(store)`/`(store datoms-key)`
call shape, same datoms-out contract, both root shapes handled. Note where the *lazy* path
ended up living: not in `read-datoms` (which is by definition the eager collect-everything
read) but in `fold` — a single-handle JVM query bypasses `read-datoms` entirely and restores
the persisted indexes lazily via psset `IStorage`, fetching nodes only as traversal reaches
them. `read-datoms` remains the correctness path every platform and every fallback
(as-of, federation) shares.

## Current-state resolution

The log is append-only, so a retraction or a cardinality-one update is itself another
datom appended after the assertion it undoes; a raw fold would see the whole history,
retractions included. `dao.space.query` resolves current state **at query time**:
`current-state-seq` (`src/cljc/dao/space/query.cljc`) walks the candidate datoms
`select-by-index` gathered for a clause and emits only those still asserted, masking any
datom whose `(e a v)` was later retracted (the `m` slot, per `dao.datom`). Cardinality-one
supersession resolves the same way, because the writer-side local transactor
(`dao.space.transact/prepare-tx`) emits an explicit `:db/retract` for the value being
superseded, which `current-state-seq` then masks, leaving the latest value. Because both
`match` and `q` select through `select-by-index`, both return only currently asserted
facts by default; a caller does not filter by `dao.datom/asserted?` by hand. `as-of`
composes: it bounds the datoms considered first, so `as-of t` yields current state *as of*
`t`. Historical reads (the raw log, retractions and all) remain available to a caller who
bypasses the library.

## Freshness

Calling them materialized views forces an explicit answer the "coordinate-aware KV" framing hid:

- A fold always reads canonical truth (the tuples directly).
- A served view can lag the log tip — it's a precomputed arrangement that must be maintained as
  tuples append.

In an append-only world this is benign: indexes only *grow* as tuples append (retractions are
semantic, above storage), so lag is monotone and bounded, and a served view is always a faithful
projection of some prefix of the log. And because any reader can reproduce a served view by
folding the canonical tuples, a served view stays "acceleration of the one medium," not a second
coordinating substrate — preserving the doc's "views are interpreter-local / not the medium" intent.

## Rulings

**Layering** — *converged* (unchanged from the 2026-07-04 discussion): convention-over-KV at
the semantic layer; coordinate-aware at the structural layer.

Every ruling below resolves by the same move: it turns out to already be answered by decisions
this project made elsewhere (the kickoff-hash namespace in `datom-spec.md`, immutable
content-addressed segments in `dao.jing.md`, `tonsky/persistent-sorted-set`'s durable-storage
API named in *Index Realization*, above) — they just hadn't been connected back to this
document. Only Ruling 1 is a genuinely new call, and it is forced, not really contestable
either: it follows directly from "storage never interprets," the one invariant repeated in
every `dao.jing*` doc.

**Ruling 1 — Expose how: no new protocol.** Not "opt-in capability protocol vs. grow
`IKVStore`" — neither. Everything the Target Architecture needs is buildable on the existing
four `IKVStore` methods (`put!`/`cas!`/`get`/`delete!`). Immutable B-Tree nodes are just more
content-addressed blobs, written with `jing/segment-key` (real as of this session — see
`dao.jing.md`, *The Segment and Root Keyspace*). Lazy traversal is a **reader-side** property:
`persistent-sorted-set`'s durable-storage API pulls a node only when the traversal reaches it,
calling `jing/get` per node; storage never scans or seeks, it just answers `get k`. The
stream's mutable root moves from `{:datoms [...]}` to `{:indexes {:eavt <segment-key>, :aevt
<segment-key>, :avet <segment-key>}}`, published with the same `cas!` as today. Any richer
"storage-side materialization" would mean a backend *computing* something, which collides
head-on with storage-never-interprets — so this isn't a coin flip, it resolves by refusing to
let storage get smarter, ever. A backend-private network shortcut (e.g. `dao.jing.dht`
batching a segment range in one round trip) stays possible, but lives entirely inside that
backend's own transport, invisible to `IKVStore` — never a protocol addition.

**Ruling 2 — Coordinate semantics: reference at naming, snapshot at read.** Not actually
either/or. A coordinate (a stream's `:root/<name>` key) is a **reference** —
dereferencing it always gets the current published root. But each individual `get` of that
root resolves to an **immutable value**: the segment tree reachable from that `:rev`. This is
Datomic's `d/db` pattern exactly (a db value is immutable; calling `d/db` again gets a fresher
one). No new mechanism — it falls straight out of "immutable segments + mutable root pointer,"
the shape `dao.jing.md` already commits to.

**Ruling 3 — Provenance: retain, via namespace stamping — not a separate mechanism.**
"Retain each tuple's source-log identity" *is* the namespace-stamping mechanism already fully
specified in `datom-spec.md`: stream identity = kickoff hash, global entity form
`[namespace offset]`, stamped only at fold time, never by a writer. This was never an
independent decision — it is the same not-yet-implemented piece `dao.jing.md`'s Current Scope
and this document's `read-datoms` Contract (above) both flag as "not yet." Nothing new to
design; it resolves by implementing what is already on paper.

**Ruling 4 — Freshness: explicit, monotone lag — confirmed, not new.** Owner-built segments
mean a reader's merged view reflects whatever root revision each source stream had *at fold
time* — never live, never blocking. This is exactly what *Freshness*, above, already argues
("a served view is always a faithful projection of some prefix of the log"). A caller wanting
fresher data re-reads the roots and re-folds; there is no promise beyond that. This ruling
was already answered by this document's own reasoning one section up — Ruling 4 just marks
it resolved rather than open.

**Ruling 5 — `dao.stream`/`dao.jing` unification: ruled out.** The coordinate stays a
convention layered over the dumb KV. Every doc in this cluster (`dao.jing.dht.md`'s division
of labor, `dao.jing.md`'s layering) depends on `dao.stream` staying upstream plumbing and
`dao.jing` staying the dumb boundary; formally unifying them would undo that.

**Ruling 6 — Index maintenance ownership: owner-built, peers-merge is the Target.** Full stop
— matches this document's own naming in *Index Realization*, above. Incremental indexing is
the degenerate case where owner and reader coincide. Rebuild-per-query stays the permanent
fallback for small stores or when no persisted index exists yet — never removed, just no
longer the only option.

**Ruling 7 — Coordinate arity: declared, at kickoff — not inferred.** `datom-spec.md`'s
kickoff metadata already includes `:dimension`: arity is declared at stream creation, at the
kickoff-hash layer. The coordinate model needs no new declaration mechanism; it reads what is
already there.
