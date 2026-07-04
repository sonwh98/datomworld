# DaoJing: The Storage and View Boundary

**Related documents:**
- [dao.space.md](file:///Users/sto/workspace/datomworld/docs/design/dao.space.md) — the tuple space that emerges when interpreters match over `dao.jing`
- [dao.stream.md](file:///Users/sto/workspace/datomworld/docs/design/dao.stream.md) — the append-only log primitive datoms are written through
- [dao.stream.file.md](file:///Users/sto/workspace/datomworld/docs/design/dao.stream.file.md) — the file-backed byte stream member logs use
- [datom-spec.md](file:///Users/sto/workspace/datomworld/docs/agents/datom-spec.md) — datoms, content-addressed identity, the gauge/base framing
- [datomic.md](file:///Users/sto/workspace/datomworld/docs/datomic.md) — the deep dive on the Datomic storage architecture this design maps to
- [0001-dao-space-as-storage-boundary.md](file:///Users/sto/workspace/datomworld/docs/design/adr/0001-dao-space-as-storage-boundary.md) — the decision this design records
- [postgres.md](file:///Users/sto/workspace/datomworld/docs/postgres.md) — the deep dive on the PostgreSQL architecture this design defines itself against

## What DaoJing Is

`dao.jing` is the **storage and view boundary**: a family of Clojure distributed datastructures designed for `datom.world` agents and interpreters. Rather than a single fixed API, `dao.jing` is a family of storage mechanisms mapped directly onto Clojure's three fundamental data structure abstractions: **Map**, **Sequence**, and **Set**. 

These three abstractions compose to cover all conceivable storage-level materialized views:
- **Sequence View (`ISeqStore`):** An ordered sequence of tuple transitions (representing the append-only log / stream of events).
- **Map View (`IMapStore` / `IKVStore`):** An associative key-value map (representing block storage of segments, hashes, or metadata).
- **Set View (`ISetStore` / `IIndexedStore`):** A mathematical/sorted set of unique tuples (representing covered indexes like EAVT/AEVT/AVET).

By implementing standard Clojure interfaces (`clojure.lang.ILookup`, `clojure.lang.Associative`, `clojure.lang.Seqable`, etc.), these distributed structures can be queried, traversed, and composed directly using Clojure's core library, while being backed by distributed and persistent storage mechanisms.

`dao.jing` combined with `dao.space.query` is what makes `dao.space` a tuple-space. `dao.space.query` acts as the interpreter that projects tuple-space semantics (associative matching, Datalog unification, and spatial/temporal decoupling) onto `dao.jing`'s raw data structure views. Storage hosts facts at rest; the tuple space is those facts under shared interpretation.

```
   dao.space.query/q   …/match          ← TUPLE SPACE reads (dao.space, separate doc)
        │
        │  Dispatches polymorphically over the most optimized
        │  materialized view exposed by the storage backend:
        ▼
╔═══════════════════ dao.jing ═══════════════════╗   ← STORAGE & VIEW boundary (this doc)
║                                                 ║
║  ┌─────────────────┐ ┌───────────────┐ ┌─────┐  ║
║  │    Sequence     │ │      Map      │ │ Set │  ║
║  │   (ISeqStore)   │ │  (IMapStore)  │ │(ISetStore)║
║  └─────────────────┘ └───────────────┘ └─────┘  ║
╚═════════════════════════════════════════════════╝
```

### Pure Syntax, Projected Semantics
Storage is **pure syntax** — it holds *form*, never *meaning*.
- A **Sequence View** knows only that it holds an ordered succession of elements.
- A **Map View** knows only that it associates keys with values.
- A **Set View** knows only that it holds unique elements sorted by a comparator.

What these data structures *denote* — "datom," "index," "attribute," "query" — is **semantics an interpreter projects onto them**, never something storage knows. This preserves the core invariant: **storage does not interpret semantically**. 

---

## The Completeness of the Three Abstractions

The decision to map `dao.jing`'s view family onto **Map**, **Sequence**, and **Set** is not an arbitrary choice of convenience; it rests on the fact that these three abstractions are mathematically and pragmatically **complete** to represent any imaginable data structure or information model.

### 1. Mathematical Completeness (Set Theory)
In Zermelo–Fraenkel set theory (ZF), the **Set** is the single primitive from which all other discrete structures are constructed:
- **Tuples:** An ordered pair $(a, b)$ is defined using Kuratowski’s definition: $\{\{a\}, \{a, b\}\}$. From pairs, we construct $N$-dimensional tuples.
- **Relations and Graphs:** A relation is a **Set** of tuples. For example, a directed graph is represented as a Set of edge tuples: `#{[node-a node-b]}`.
- **Maps:** A Map is a relation (a **Set** of pairs) where the key (first element) is unique.
- **Sequences:** A Sequence is a Map whose keys are sequential natural numbers: `{0 v0, 1 v1, 2 v2, ...}`.

Thus, mathematically, the **Set** is the foundation. Maps and Sequences are specialized constraints over Sets.

### 2. Pragmatic Completeness (Clojure's Composition)
In software architecture, these three abstractions represent the three core axes of information:
- **Sequence** represents *chronology, streams, and time* (the append-only log / `dao.stream` timeline).
- **Set** represents *uniqueness, constraints, and indexing* (the covered B-Tree indexes / `EAVT` relations).
- **Map** represents *entity projection and attribute association* (the document / entity view).

Every complex data structure decomposes cleanly into these three:
- **Trees & ASTs:** Represented as nested Maps, or a Sequence of path tuples.
- **Graphs:** Represented as an adjacency Map mapping nodes to Sets of neighbors, or a flat Set of edge tuples.
- **Matrices & Tensors:** Represented as a Map of coordinate tuples to values, or nested Sequences.
- **Objects & Records:** Represented as a Map of attribute keywords to values under a schema.

This completeness is why `datom.world` forbids hidden abstractions: graphs and trees are not primitives, so they are never represented directly in storage. Instead, we write canonical facts to a **Sequence**, index them as a sorted **Set**, and read them as a **Map**. By implementing Clojure's core interfaces for these three abstractions, `dao.jing` makes this completeness directly available to the Clojure core library.

---

## The Storage-Level Views

A `dao.jing` backend implements one or more of these core abstractions depending on its capability and environment.

### 1. The Sequence View (`ISeqStore` / `ITupleStream`)
*   **Abstraction:** Sequence (ordered, iterable collection).
*   **Physical Realization:** An append-only log file, a ringbuffer, a network stream, or a local vector.
*   **Role:** The canonical intake path. Every agent acts as a transactor by appending new facts to its own sequence view. A query engine reading *only* a sequence view must fold and index all elements dynamically in memory (rebuild-per-query).

### 2. The Map View (`IMapStore` / `IKVStore`)
*   **Abstraction:** Map (associative key-value).
*   **Physical Realization:** An in-memory hash map (`KVMem`), a local compaction-based file store (`KVFile`), or a remote DHT node.
*   **Role:** The segment-persistence layer. B-Tree index nodes (EAVT/AEVT/AVET) are serialized by the writer into chunks and stored in the map view under content-addressed keys, while mutable root references are updated via CAS.

### 3. The Set View (`ISetStore` / `IIndexedStore`)
*   **Abstraction:** Set (unique elements, sorted).
*   **Physical Realization:** An index engine (e.g., SQLite, PostgreSQL, or a memory-resident B-Tree) that can serve sorted ranges of tuples.
*   **Role:** Storage-accelerated seeks. Instead of downloading and parsing B-Tree segments from a Map View, the query engine makes prefix/range seeks directly against the Set View (e.g., *find all tuples matching `[?e :work/status :todo]`*).

---

## Index Realization & Query Adaptability

How a query is executed depends on which view the backend exposes. The `dao.space.query` engine adapts dynamically:

1.  **Fast Path (Indexed-Set View):** If the store exposes `ISetStore`, the query engine translates Datalog clauses into direct prefix-range seeks on the store. The storage engine executes the index lookup structurally.
2.  **Standard Path (Segment-Map View):** If the store only exposes `IMapStore` (like the default `IKVStore`), the query engine uses an `IStorage` adapter to lazily fetch B-Tree chunks from the map, rebuilding the index traversal in-process (Datomic-peer style).
3.  **Fallback Path (Sequential-Log View):** If the source is a raw `ISeqStore` or a vector, the query engine reads all facts, folds them into a temporary local sorted set, and performs the query.

All paths are semantically identical because they operate on the same tuple space, but they move the compute/materialization boundary to match the storage capability.

---

## Structural Ignorance: Format Stability Without the Engine

PostgreSQL couples storage to structures for speed, paying with vacuum/WAL complexity. We decouple: **readability in place is a property of the byte layout, not the storage engine.**
Even when a backend implements the sorted `ISetStore` view, it remains **structurally aware but semantically ignorant**:
- It knows how to compare and order tuple elements lexicographically.
- It does **not** know what the fields mean, how logic variables unify, or how Datalog joins are executed.

This keeps storage simple enough to run on decentralized nodes or in-memory caches, while supporting opt-in structural acceleration.

---

## Hardware Metaphors (The Map View)

For backends implementing the `IMapStore` (`IKVStore`) view, proven hardware patterns map directly as software abstractions:
-   **SSD Flash Translation Layer & GC:** Compaction in the `KVFile` backend (`compact-store!`) mirrors an SSD writing to new blocks and reclaiming orphaned space in the background.
-   **NVMe Parallel Queues:** Zero contention is achieved by giving every agent its own single-writer `ISeqStore` sequence.
-   **Storage Tiering (L1/L2 Caches):** Decorators (like a caching store in front of a network store) handle tiering transparently to the reader.
-   **RAID and Erasure Coding:** Middleware can mirror (`put!`) and split segments across multiple remote stores for redundancy.

---

## Lineage

-   **Lisp/Clojure** provides the core conceptual architecture: Map, Sequence, and Set are the complete vocabulary for all data structures and views.
-   **Datomic** provides the storage discipline: separating Transactor (sequence appends) from Storage (map segments and set indexes) and Query.
-   **Plan 9** provides the location-transparent stream/log model for the sequence views.

The synthesis: **`dao.jing` is a family of Clojure distributed datastructures designed for `datom.world` agents and interpreters.**
