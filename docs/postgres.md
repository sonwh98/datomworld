# PostgreSQL Architecture Deep Dive

## Overview

`docs/datomic.md` describes what happens when the integrated database is decomposed into three independent components. This document is the complementary deep dive into the monolith itself: how PostgreSQL bundles query execution, write serialization, and storage/indexing into **one binary that intimately understands every byte it stores** — and what that structural awareness buys and costs. The lesson `dao.jing` draws from this architecture — format stability without structural awareness — lives with the boundary itself, in `docs/design/dao.jing.md` (Structural Ignorance).

```
┌─────────────────────────────────────────────────────────┐
│                   PostgreSQL Server                     │
│                                                         │
│  postmaster ──forks──► backend per connection           │
│                                                         │
│  ┌──────────┐  ┌───────────┐  ┌───────────────────────┐ │
│  │  Planner │  │   Lock    │  │    Buffer Manager     │ │
│  │ Executor │──│  Manager  │──│  (shared_buffers,     │ │
│  │          │  │  WAL, MVCC│  │   8KB pages, B-trees) │ │
│  └──────────┘  └───────────┘  └───────────────────────┘ │
│      every layer knows the page and tuple layout        │
└─────────────────────────────────────────────────────────┘
```

The central design commitment is **structural awareness**: the storage engine is not a byte store that happens to hold indexes — it *is* the index machinery. The buffer manager, the WAL, the lock manager, MVCC, and the B-tree code all share intimate knowledge of one binary layout (the 8KB page). Every strength and every limitation in this document flows from that one commitment, the exact mirror image of Datomic's dumb-store bet.

| Responsibility               | Where it lives                          | How it is coupled to the page format |
| :--------------------------- | :-------------------------------------- | :----------------------------------- |
| Query planning & execution   | Backend process                         | Executor reads tuples in place       |
| Transaction serialization    | WAL + lock manager + MVCC               | Tuple headers carry visibility       |
| Storage / indexing           | Buffer manager + access methods         | It *is* the page format              |
| Caching                      | `shared_buffers` (+ kernel page cache)  | The cache unit is the page           |

The rest of this document: **§1** process and memory architecture · **§2** the 8KB page and format stability · **§3** MVCC · **§4** WAL · **§5** indexes · **§6** locking · **§7** TOAST · **§8** extensibility and the cost of awareness.

---

## 1. Process and Memory Architecture

PostgreSQL is a **process-per-connection** system coordinated through shared memory:

* **postmaster** — the supervisor. It listens, authenticates, and forks one **backend process** per client connection. Backends do their own parsing, planning, and execution; there is no separate "query server" tier.
* **Background processes** — checkpointer, background writer, WAL writer, autovacuum launcher/workers, logical/physical replication workers, and the stats collector. These are the maintenance half of structural awareness: they exist because pages must be flushed, WAL must be recycled, and dead tuples must be reclaimed (§3).
* **Shared memory** — chiefly `shared_buffers`, an array of 8KB page slots shared by all processes, plus lock tables, the WAL buffers, and the commit-log (`pg_xact`) buffers. Per-backend memory (`work_mem`, `maintenance_work_mem`) handles sorts, hashes, and index builds.

### The double-buffering cost

PostgreSQL reads and writes data files through **buffered file I/O**. A page fetched from disk is copied by the kernel into the OS page cache, then copied again into `shared_buffers`. The same page can therefore be resident in RAM twice. This is the well-known double-buffering problem; the asynchronous-I/O and direct-I/O work in recent releases (the AIO subsystem, `debug_io_direct`) exists to address it. The consequence for any "zero-copy" claim is spelled out in §2.

---

## 2. The 8KB Page and Format Stability

Everything PostgreSQL stores — heap tables, B-tree nodes, TOAST chunks — lives in fixed **8KB pages** with one layout (`src/include/storage/bufpage.h`):

```
 0                                                              8192
 ┌────────────┬──────────────┬───────────►      ◄──────────┬────────┐
 │ PageHeader │ ItemId array │  free space │ tuple3 tuple2 │special │
 │ (24 bytes) │ (grows down) │             │ tuple1 (grow up)│(index) │
 └────────────┴──────────────┴─────────────┴───────────────┴────────┘
```

* The **ItemId array** (line pointers) grows forward from the header; each 4-byte entry holds the offset and length of one tuple. Tuples themselves grow backward from the end of the page. A tuple's stable address is its **ctid** — `(page number, line pointer index)` — which is what indexes point at.
* Each heap tuple begins with a **HeapTupleHeader**: `xmin`/`xmax` (the inserting and deleting transaction ids, §3), the ctid, an infomask of flag bits (including **hint bits**, cached visibility verdicts), and a null bitmap. User columns follow at their **aligned offsets** — fixed-width values are read directly at computed positions.

### Format stability, stated precisely

The property doing the performance work is **format stability**: the on-disk page layout *is* the in-memory layout. The executor reads tuples in place through a direct C pointer into a page in `shared_buffers` — line pointers are page offsets, fixed-width columns sit at aligned positions, and there is no deserialization step converting a disk format into a memory format.

Two caveats keep the claim honest:

1. **The transfer into RAM is not zero-copy.** Buffered I/O copies pages from the kernel page cache into `shared_buffers` (§1). The no-parse property holds for reads of pages *already resident*, not for the disk-to-RAM path.
2. **Variable-length values may need detoasting** and decompression (§7), which is a parse. And reads are not even purely read-only: the first visibility check on a tuple may set hint bits, dirtying the page — a write caused by a read.

PostgreSQL obtains format stability by making every layer of the engine structurally aware of this one layout. Whether awareness is the only way to obtain it is the question `dao.jing` answers in the negative (`docs/design/dao.jing.md`, *Structural Ignorance*).

---

## 3. MVCC: Versions Live in the Heap

PostgreSQL's concurrency story is **multi-version concurrency control with old versions stored inline**:

* An `UPDATE` never modifies a tuple in place. It writes a **new tuple version** (usually in the same page — a HOT update — otherwise elsewhere) and stamps the old version's `xmax`. A `DELETE` just stamps `xmax`. The heap accumulates every version until vacuum removes the dead ones.
* Every snapshot is a set of transaction-id horizons. A tuple is visible if its `xmin` is committed-and-in-the-past and its `xmax` is absent, aborted, or in-the-future. Visibility is computed per tuple, per read, against `pg_xact` — with **hint bits** caching the verdict in the tuple header to skip the lookup next time.
* **Vacuum** is the collector this design mandates: autovacuum workers continuously scan for dead tuples, reclaim their line pointers, update the free-space and visibility maps, and **freeze** old `xmin` values to defend against 32-bit transaction-id wraparound. Vacuum is not optional hygiene; it is a structural consequence of keeping versions in the heap.

| | PostgreSQL | Datomic |
| :--- | :--- | :--- |
| Old versions | In the heap, until vacuumed | In the log/index, **forever** |
| History | A transient implementation detail | A first-class, queryable dimension |
| Reclamation | Autovacuum (continuous, load-bearing) | Background indexing compacts; history kept |

Both systems are "append-mostly" at the tuple level; the difference is intent. PostgreSQL keeps old versions only long enough to serve concurrent snapshots — history is garbage. Datomic keeps them as the product — history is the database.

---

## 4. WAL: Recovery, Not History

The **write-ahead log** is a physical redo log: every page modification is described by a WAL record (identified by LSN, its byte position in the log stream) that must be flushed before the dirtied page may be written. Commit means "WAL flushed through this record."

* After each **checkpoint**, the first touch of any page logs a **full-page image**, so crash recovery can restore torn pages before replaying deltas. Checkpoint cadence trades WAL volume against recovery time.
* Replication is WAL shipping: streaming replicas replay the physical log; logical decoding re-derives row changes from it.
* The WAL is *not* history in Datomic's sense. It is recycled after checkpoints and replication catch-up; you cannot query "the database as of last Tuesday" from it. Point-in-time recovery can *restore* a past state from a base backup plus archived WAL, but past states are not live, queryable values.

The WAL is also structurally aware: its records are typed per access method (heap insert, B-tree split, ...), and each type's replay code understands the page layout it patches. Adding a new storage structure means adding new WAL record types or using the slower generic-WAL path (§8).

---

## 5. Indexes: Synchronous, In-Engine

A PostgreSQL index is a separate structure of the same 8KB pages, maintained by the engine **synchronously on the write path**:

* The default access method is the Lehman–Yao **B-tree** (`nbtree`). The engine performs page splits, sibling-pointer maintenance, and (since v12) deduplication — all inside the storage layer, all WAL-logged, all vacuum-aware. Index entries point at heap ctids; a non-HOT update must insert new entries into **every** index on the table.
* Other access methods — GIN, GiST, SP-GiST, BRIN, hash — plug into the same machinery: each supplies operators plus WAL and vacuum integration.

| | PostgreSQL | Datomic |
| :--- | :--- | :--- |
| Index maintenance | Inline, on every write | Deferred to background indexing jobs |
| Write cost | Touch every relevant index synchronously | Log append + novelty (cheap) |
| Who balances the tree | The storage engine | The Peer/Transactor library, above storage |

This is structural awareness at its most concrete: the B-tree is not data the engine stores; it is code the engine runs.

---

## 6. Locking and Coordination

PostgreSQL is genuinely **multi-writer**, and pays for it with a coordination stack Datomic's single-writer design deletes:

* A **heavyweight lock manager** in shared memory mediates table-level and advisory locks, with a deadlock detector that wakes periodically to find cycles and abort a victim.
* **Row locks** are cleverly cheap — taken by stamping the tuple's own header (`xmax`, infomask), with **multixacts** allocated when several transactions share a lock on one row.
* Below both, **LWLocks** and buffer-content locks serialize access to shared-memory structures and individual pages; a page being split is briefly locked against concurrent readers.

The comparison tables in `docs/datomic.md` (Overview, "Concurrency model") are the other half of this section: Datomic collapses this entire stack into one CAS on a root pointer by allowing exactly one writer. PostgreSQL keeps concurrent writers and accepts the lock manager, deadlock detection, and MVCC bookkeeping as the price.

---

## 7. TOAST: Where No-Parse Ends

A tuple cannot span pages, so it must fit in one 8KB page. Variable-length values that would push a tuple past a threshold (roughly 2KB, tuned so at least four tuples fit per page) are **TOASTed** — "The Oversized-Attribute Storage Technique":

1. **Compress in place** (pglz or lz4). If the tuple now fits, done.
2. **Move out of line.** The value's bytes are split into **chunks** of just under 2KB (`TOAST_MAX_CHUNK_SIZE`, sized so four chunk rows fit per page), and each chunk becomes an ordinary row in a hidden companion table, `pg_toast.pg_toast_<oid>`, with schema `(chunk_id, chunk_seq, chunk_data)`. The main tuple keeps only an 18-byte TOAST pointer: the chunk id, the value's raw and compressed sizes, and the TOAST table's oid.

Reading the value back — **detoasting** — is the reverse: fetch all rows with that `chunk_id` via the TOAST table's index on `(chunk_id, chunk_seq)`, concatenate the chunks in sequence order, decompress. The TOAST table is itself ordinary heap pages with an ordinary B-tree index, MVCC-versioned and vacuumed like everything else — structural awareness eating its own dog food.

Two mitigating details: TOAST is per-column and lazy — only varlena types (`TEXT`, `BYTEA`, `JSONB`, arrays) are candidates, the strategy is settable per column (`PLAIN`/`MAIN`/`EXTENDED`/`EXTERNAL`), and a query that never touches the wide column never pays the detoast, since the pointer just rides along in the tuple. And because chunks are indexed by sequence, `substring()` on an uncompressed (`EXTERNAL`) value fetches only the chunks covering the requested range.

TOAST marks the boundary of the in-place read: detoasting is extra page fetches, allocation, and decompression on every read that touches the value — a genuine parse, the thing §2's format stability exists to avoid. This is also where "just store your custom structure in a column" breaks down: a serialized structure in `BYTEA`/`JSONB` is opaque to the engine, subject to TOAST, and must be deserialized on every read. Inside the page format you get no-parse reads; outside it you are back to serialization — with no middle ground, because the format belongs to the engine (§8).

### The compression trade-off

Compression and zero-copy are incompatible *at the same layer*: compressed bytes cannot be traversed in place, and decompression materializes a second copy by definition. Every system picks a side per layer, and where it puts the boundary reveals what it thinks its bottleneck is:

* **PostgreSQL: uncompressed where reads happen.** Heap and index pages are stored raw precisely so resident pages can be read in place (§2). Compression exists only at the edges — TOAST values here, and WAL — and both are exactly where the no-parse property already ends. A compressed TOAST value pays a decompress on *every* detoast; PostgreSQL accepts that only for values too big to live in a page anyway.
* **Datomic: compressed at rest, decoded once, cached.** Datomic's segments travel from remote storage over a network, so bytes-moved dominates; they are stored Fressian-encoded and compressed, and the decode cost is paid once per segment *fetch*, not per read — the decoded segment lives in the Peer's object cache (see `docs/datomic.md` §4), and the hot path reads the decoded form.

The general resolution: **compress below the boundary where reads happen, never at it.** Filesystem- or block-level compression can sit transparently under an "uncompressed" page format; wire compression can apply per transfer; but the representation the reader actually traverses must be raw bytes. The choice is also a bet on access pattern: uncompressed-for-zero-copy earns its space premium in proportion to how many times each resident byte is read, while for cold, read-once data, compress-and-decode-once wins — the decode was unavoidable and the I/O was smaller.

---

## 8. Extensibility: The Cost of Structural Awareness

PostgreSQL is famously extensible *within* its frame — new types, operators, index access methods, even pluggable table access methods (since v12) — but the frame is exactly the coupling described above. A genuinely new storage structure must integrate with:

* the **buffer manager** (live in 8KB pages, participate in eviction and checkpoints),
* the **WAL** (define replayable record types, or accept the slower generic-WAL path),
* **MVCC and vacuum** (expose visibility, be reclaimable),
* the **lock manager** (define its concurrency protocol).

That is why serious extensions are multi-year C projects: `pgvector` had to implement its HNSW and IVFFlat structures as page-based, WAL-logged, vacuum-aware access methods to be a first-class index. And it is why the pluggable table-AM efforts (zheap, OrioleDB) are engine-scale undertakings rather than plugins in the ordinary sense. Storing a structure the engine does not understand means `BYTEA`/`JSONB` and reparsing (§7). The two options are: teach the engine your structure (years), or give up in-place reads (every read).

`dao.jing` takes a third option — make in-place readability a property of the byte layout rather than of the engine — and what that costs and buys is argued where the boundary is specified: `docs/design/dao.jing.md`, *Structural Ignorance*.
